# AR Measure — Gated Implementation Plan

Target: Android AR measurement app. Rust geometry/measurement core (via UniFFI) +
Kotlin ARCore/UI shell. Each phase has an explicit exit gate — OpenCode must not
proceed to the next phase until the gate's tests pass and are shown passing.

Role boundary: OpenCode implements. Every phase ends with a diff + test output
handed back for Claude audit before merge. No phase is "done" on OpenCode's own
assessment alone.

Repo layout (Gitea, owner `sol`):
```
ar-measure/
├── core/                  # Rust workspace member — geometry/measurement engine
│   ├── src/
│   │   ├── lib.rs
│   │   ├── point.rs
│   │   ├── measurement.rs
│   │   ├── confidence.rs
│   │   └── uniffi_bindings.udl (or proc-macro exports)
│   └── Cargo.toml
├── android/                # Kotlin app module
│   └── app/src/main/java/.../
├── xtask/                  # cargo-ndk build orchestration if needed
└── docs/
    └── plan.md             # this file
```

---

## Phase 0 — Workspace bootstrap

**Scope**
- `cargo new --lib core`, workspace `Cargo.toml`
- Add `uniffi` dependency, `uniffi::setup_scaffolding!()` in `lib.rs`
- Empty Android Studio project (Kotlin, min SDK per current ARCore requirements — verify via web search, do not assume)
- `cargo-ndk` + Gradle plugin wired so `core` builds to `.so` per ABI and lands in `android/app/src/main/jniLibs`
- One trivial exported function: `pub fn ping() -> String { "pong".into() }`

**Gate 0 tests** (OpenCode must produce and pass all before Phase 1):
1. `cargo test -p core` passes (even with zero real tests, must compile clean, zero warnings)
2. `cargo build -p core --target aarch64-linux-android` succeeds via cargo-ndk
3. Android instrumented test: call `Core.ping()` from Kotlin, assert `"pong"` — must run on-device or emulator, not just compile
4. Fresh clone + build from scratch (`git clone` into tmp dir, run full build) succeeds with zero manual steps beyond documented setup — OpenCode records the exact command sequence in `docs/setup.md`

**Do not proceed** until gate 4 passes on a clean clone. This catches "works on my machine" FFI toolchain drift early, before any real logic exists to blame it on.

---

## Phase 1 — Rust geometry core (no ARCore, no UI)

**Scope**
- `Point3 { x: f32, y: f32, z: f32 }`
- `distance(a: Point3, b: Point3) -> f32` — straight Euclidean
- `Confidence { High, Medium, Low }`
- `MeasurementSource { Depth, Plane, FeaturePoint }`
- `Measurement { start: Point3, end: Point3, distance: f32, confidence: Confidence, source: MeasurementSource }`
- Confidence-scoring function: given `MeasurementSource`, camera-to-point distance (meters), and tracking-quality flag, return `Confidence`. Bracket rule: distance outside 0.5m–5.0m from camera → downgrade one confidence tier (never below `Low`); `FeaturePoint` source caps at `Medium` regardless of distance; `Depth` source with in-bracket distance and good tracking → `High`.
- All exported via `#[uniffi::export]`

**Gate 1 tests** (Rust-only, `cargo test -p core`, no device needed):
1. `distance()` unit tests: known 3-4-5 triangle, zero-distance (same point), negative coordinates, large coordinates (>1000m) don't overflow/NaN
2. Confidence scoring table test — parametrized over all `(source, distance_bracket, tracking_ok)` combinations, asserting exact expected tier per the rule above (this is the one Claude will audit hardest — the rule must be mechanically checkable, not vibes)
3. Property test (use `proptest` or manual fuzz loop): `distance(a, b) == distance(b, a)` for 1000 random point pairs (symmetry)
4. Property test: `distance(a, a) == 0.0` for 1000 random points
5. Serialization round-trip if `Measurement` is persisted later — skip for now, flag as TODO comment only, do not implement persistence in this phase (scope creep guard)
6. UniFFI binding smoke test from Kotlin: construct a `Measurement`, read back each field, confirm enum variants cross the FFI boundary correctly (this is the actual risk area per the UniFFI research — enums and structs must round-trip cleanly)

**Explicit non-goals for this phase** (OpenCode must not touch): no ARCore, no rendering, no raycasting, no persistence. If OpenCode's diff touches anything outside `core/`, reject and re-scope.

---

## Phase 2 — ARCore session + plane detection (Kotlin, no measurement logic yet)

**Scope**
- `ArSession` wrapper: init, permission handling, lifecycle (pause/resume tied to Activity)
- Enable plane detection (horizontal + vertical)
- Camera preview rendering (GL surface or CameraX passthrough per ARCore sample pattern)
- Device capability check screen: report ARCore support, Depth API support, sensor availability — matches the "device capabilities" table from the design doc — before allowing entry into measure mode
- **No point selection, no measurement, no line rendering yet**

**Gate 2 tests**:
1. Unit test (Robolectric or similar): capability-check logic returns correct booleans given mocked `Session` support flags — all four combinations of (ARCore supported × Depth supported)
2. Instrumented test: app launches, requests camera permission, session initializes without crash on the actual test device/emulator used
3. Manual verification checkpoint (OpenCode cannot automate this — must report to Claude with a screen recording or description): point camera at a real flat surface, confirm ARCore plane detection visually indicates a plane found (log output or debug overlay is acceptable, doesn't need polished UI yet)
4. Session correctly pauses/resumes across an Activity lifecycle test (rotate device or background/foreground the app) without leaking the native session or crashing

**Gate 2 is the first one requiring physical/emulator device interaction with real camera input — flag to Sol if CI/emulator lacks camera passthrough support, this may need to run on Sol's physical device rather than fully automated.**

---

## Phase 3 — Raycast + point selection → wire into Rust core

**Scope**
- Tap handler: screen coordinate → ARCore hit-test (plane hit only for this phase, per the MVP tightening — depth hit-test deferred to Phase 5)
- Hit result → `Point3`, passed across FFI to `core::distance` or held pending second point
- State machine per the design doc: `Idle → SelectingFirstPoint → Measuring{start} → Complete{start,end,distance}` — implement in Kotlin (sealed class) or Rust (exported enum), pick one, do not duplicate state in both layers
- Live line render between first point and current camera-projected point while in `Measuring` state
- Distance label showing live-updating `core::distance()` result in meters

**Gate 3 tests**:
1. State machine unit tests: every legal transition succeeds, every illegal transition (e.g. second tap while `Idle`) is a no-op or explicit rejection, not a crash — enumerate all transition pairs, not just happy path
2. Instrumented test: simulated tap events (MotionEvent injection) on a plane-detected scene produce a `Complete` state with non-null `Measurement`
3. Cross-FFI test: measurement distance computed via Rust core matches an independently-computed reference value (compute expected distance in the test itself from known ARCore pose data, compare within float epsilon)
4. Manual checkpoint: two taps ~1m apart on a real flat surface (e.g. desk) produce a displayed measurement within reasonable real-world tolerance (report actual vs. expected to Claude — this is where tracking accuracy in practice gets validated, not just logic)
5. Reset/cancel path: mid-measurement cancel returns cleanly to `Idle`, no dangling AR anchors leaking (check anchor count before/after)

---

## Phase 4 — Tracking quality + measurement validation

**Scope**
- Subscribe to ARCore `TrackingState` per frame (poll-based, per the FFI research — do not push per-frame data across UniFFI callbacks)
- Reject/flag measurements per the design doc's validation list: tracking lost, insufficient feature points, excessive camera motion during the two taps
- Surface confidence tier (from Phase 1's Rust scoring) in the UI label ("High confidence" / "Move closer to improve accuracy")
- Distance-from-camera check feeding the 0.5m–5m bracket rule from Phase 1

**Gate 4 tests**:
1. Unit test: tracking-state transitions (Normal → Limited → Normal) correctly gate whether a tap is accepted
2. Unit test: simulated "excessive motion between taps" (mock large pose delta) correctly downgrades or rejects the measurement
3. Confidence tier displayed in UI matches what Rust core would independently compute for the same synthetic inputs — this is an integration test bridging Phase 1's unit-tested logic to the actual UI, catches "logic is right but wiring is wrong"
4. Manual checkpoint: deliberately shake the device mid-measurement, confirm the app surfaces a "tracking lost" state rather than silently returning a bad number

---

## Phase 5 — Depth API hit-test + Raw Depth for higher-accuracy point selection

**Scope**
- Enable Depth API, check per-device support (already surfaced in Phase 2's capability screen)
- Raycast priority order per design doc: Depth hit → Plane hit → Feature point hit → fail
- `MeasurementSource` on the resulting `Measurement` correctly reflects which tier was used
- Raw Depth API specifically for the 0.5m–5m accuracy bracket, not the smoothed depth map, per the research above

**Gate 5 tests**:
1. Unit test: raycast fallback chain — mock each tier failing in sequence, confirm it falls through correctly and never silently returns null when a lower tier would have succeeded
2. Instrumented test: on a Depth-API-capable device/emulator, a tap resolves to `MeasurementSource::Depth`; on a mocked non-Depth-capable path, same tap resolves to `MeasurementSource::Plane`
3. Manual checkpoint: compare a Depth-sourced measurement against a Plane-sourced measurement of the same real-world distance — Depth should be equal or more accurate, report both to Claude
4. Confirm confidence tier from Phase 1 responds correctly to the now-real `MeasurementSource` value (this was stub-tested in Phase 1 with synthetic enums — now test with real ARCore-derived sources)

---

## Phase 6 — Persistence

**Scope**
- `MeasurementRepository` + local storage (SQLite via rusqlite in Rust core, consistent with Sol's existing stack preference)
- Save/load a `Measurement` list, grouped by named "project" (per design doc's Living Room / Bedroom grouping example)
- Serialization round-trip test deferred from Phase 1 — implement now

**Gate 6 tests**:
1. Rust unit test: save then load a `Measurement`, all fields including enums round-trip exactly
2. Rust unit test: corrupt/missing DB file handled gracefully (no panic), returns typed error
3. Instrumented test: save a measurement in the app, kill and relaunch the process, confirm it's still listed
4. Migration test stub: even a single-version schema should have a documented migration path — OpenCode writes the first migration file even though there's nothing to migrate yet, so the pattern exists before Sol needs it under pressure later (matches the FlexiBooks migration pattern already in use)

---

## Cross-cutting rules for every phase (OpenCode must follow throughout)

- **No phase merges on OpenCode's self-assessment.** Diff + test output goes to Claude for audit first, matching Sol's existing role boundary.
- **No skipping ahead.** If Phase 3 reveals Phase 1's confidence bracket is wrong in practice, fix Phase 1, re-run Phase 1's gate tests, then resume — don't patch around it in Phase 3.
- **Manual checkpoints are not optional.** Where a gate lists a manual checkpoint, OpenCode reports actual observed behavior (numbers, screenshots, or precise description) — "should work" is not a passing report.
- **Scope creep guard.** Each phase's "non-goals" (explicit or implied by what's not listed) are enforced — a diff touching files outside the current phase's stated scope gets flagged back, not merged.
- **Zero-warning policy on Rust** (`cargo clippy` clean) before any gate is considered passed, consistent with existing MIT Services / Concerto standards.
