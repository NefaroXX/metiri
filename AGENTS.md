# Metiri — AR Measure

## Active Context
- **All Phase 3 automated device gates are GREEN** (2026-08-17 r4 run, evidence `/c/msys64/tmp/opencode/instr_phase3r4_*.txt`): 3.2 `ArMeasurementTest` OK (1) 11.3s, 3.5 `ArMeasurementCancelTest` OK (2) 14.7s, plus earlier 3.3 / follow-up A / 2.2+2.4. Gate 3.4 manual checkpoint (TASK-018): camera feed FIXED + verified on-device (2026-08-18 10:23, uvs 0.5→varied, screencap 55KB→566KB). Sol's calibration: **tile 33cm → app 32cm (−3%, finger error — PASS)**, earlier 1m→0.49m was pre-fix flat-field / off-plane tap. New finding: **plane stability is the blocker** — A20 shows intermittent planes, intersecting/overlapping fragments, floating measurements.
- Camera-stream root cause found + fixed + on-device verified (2026-08-18 10:23): missing NDC input to `transformCoordinates2d` → all UVs 0.5 → single-texel flat field (present since Phase 2). Fix: init uvs to NDC corners; diagnostic logs added; screencap 55KB→566KB, first-frame uvs 0.5→varied. Also added surface/session-aware texture rebinding (`boundSessionId` guard). JVM 34/34 green, APKs rebuilt. **Next: decide Phase 3 close vs plane-stability hardening before commit.**

## TODO Board
- [x] TASK-001: Amend plan (6 amendments) + move docs to `docs/`
- [x] TASK-002: Provision toolchain (Rust targets, cargo-ndk, uniffi CLI, JDK, Android SDK/NDK)
- [x] TASK-003: Phase 0 core scaffold + Gate 0.1 (`cargo test`/clippy clean)
- [x] TASK-004: Phase 0 Android scaffold + Gate 0.2 (`cargo ndk`, 3 ABIs)
- [x] TASK-005: App + androidTest APK build green (JNA dep added)
- [x] TASK-006: Gate 0.3 — instrumented ping test PASSED on emulator `metiri` (AVD)
- [x] TASK-007: Commit + push Phase 0 to Gitea (committed as `a25ffee`, pushed)
- [x] TASK-008: Gate 0.4 — remote-clone build PASSED against pushed `a25ffee` (emulator test included)
- [x] TASK-009: Phase 1 — Rust geometry core (distance, confidence, enums, UniFFI round-trip): 8/8 Rust + 5/5 instrumented tests green
- [x] TASK-010: Commit + push Phase 1 (committed as `396a11a`, pushed)
- [x] TASK-011: Phase 2 — ARCore session + plane detection + capability screen: gates 2.1 (5/5 unit), 2.2 + 2.4 (OK 2 tests on SM-A205F), 2.3 (planes 1→2→3 on SM-A205F) all PASSED
- [x] TASK-012: Commit + push Phase 2 (committed as `051488c`, pushed)
- [x] TASK-013: Phase 2 audit — accepted as-is by Claude; follow-ups carried into Phase 3
- [x] TASK-014 (Phase 3 carry-over): gate 2.4 follow-up — `ArSessionManager.closedSessionCount` + `ArSessionCloseTest` written (device assertion queued in TASK-017)
- [x] TASK-015 (Phase 3 carry-over): `shouldRetryAvailabilityCheck` helper extracted + 5 JVM tests PASS
- [x] TASK-016: Phase 3 implementation + JVM gates — state machine (22 tests, gate 3.1), retry helper (5 tests), 32/32 unit green, APKs build, oracle ship verdict
- [x] TASK-016: Phase 3 implementation + JVM gates — state machine (22 tests, gate 3.1), retry helper (5 tests), 32/32 unit green, APKs build, oracle ship verdict
- [x] TASK-017: Phase 3 device gates on SM-A205F — ALL GREEN (r4 run 2026-08-17): `CrossFfiDistanceTest` (3.3), `ArMeasurementTest` (3.2), `ArMeasurementCancelTest` (3.5), `ArSessionCloseTest` (A), `ArSessionLifecycleTest` (2.2/2.4 regression). Also includes crash-fix verification (zero SIGSEGV). Motion requirement: A20 needs active human-held motion; timeouts are 240s.
- [ ] TASK-018: Phase 3 gate 3.4 manual checkpoint with Sol — two taps ~1m apart on a desk, report actual vs expected distance. Blocked on cable replacement + on-device verification of the camera-visibility fix (user could not see the feed to place taps).
- [ ] TASK-019: Phase 3 audit (diff + test evidence) → commit + push once gates pass (3.2/3.5/3.3/A/2.2-2.4 green; 3.4 pending)

## Decisions Log
| Date | Decision | Rationale |
|------|----------|------------|
| 2026-08-16 | Toolchain pins: AGP 9.1.1, Gradle 9.3.1, uniffi 0.32.0, NDK 28.2.13676358, compileSdk 36, minSdk 24 | Kotlin 2.4.x max fully-supported AGP is 9.1; Gradle 9.1+ runs on the existing JDK 25 |
| 2026-08-16 | No `kotlin-android` plugin — AGP 9 built-in Kotlin | AGP 9 rejects the standalone plugin; compiler version is AGP-managed |
| 2026-08-16 | JNA 5.19.1 **`@aar`** runtime dependency in app | UniFFI JNA-backend bindings need `com.sun.jna`; the AAR ships `libjnidispatch.so` per ABI — the plain jar crashes with UnsatisfiedLinkError on-device (Gate 0.3 catch) |
| 2026-08-16 | SDK at `C:\Android\Sdk`; `ANDROID_NDK_HOME` exported | cargo-ndk auto-detect only covers Studio's default SDK location |
| 2026-08-16 | Phase 3 state machine = Kotlin sealed class | Plan amendment: UI-flow state belongs in the shell; Rust core stays stateless |
| 2026-08-16 | `Cargo.lock` committed at workspace root | Reproducible builds across the pinned toolchain |
| 2026-08-16 | Build sequence runs from the repo root (not `cd core`) | Workspace `target/` lives at the root; `cd core` breaks bindgen's `--library` path (Gate 0.4 clean-copy catch) |
| 2026-08-16 | Emulator is viable — WHPX works despite WMI `VirtualizationFirmwareEnabled=False` | `HypervisorPresent=True`, `emulator -accel-check` says WHPX usable; boots ~3 min. Phase 4+ ARCore still needs a physical device |
| 2026-08-16 | Confidence table: FeaturePoint is always `Medium` (distance AND tracking have no effect); bracket is inclusive [0.5, 5.0]; NaN distance → out-of-bracket | Plan's gate 1.2 rule leaves these two spots open — flagged for Claude audit; encoded as a literal 12-arm match in `score_confidence` + duplicated in the table test |
| 2026-08-16 | ARCore client `com.google.ar:core:1.53.0` pinned; ARCore service 1.53.0 on BOTH emulator (x86 build) and device (arm64 build) | SDK client requires service ≥ 1.53; mismatched service versions are an extra variable to eliminate |
| 2026-08-16 | Depth mode guarded: `isDepthModeSupported(AUTOMATIC)` else `DISABLED` | `Config.DepthMode.AUTOMATIC` unconditionally throws `UnsupportedConfigurationException` on devices without Depth support (observed on Galaxy A20 SM-A205F) |
| 2026-08-16 | Phase 2 gates run on the physical device (SM-A205F), not the emulator | Emulator's camera2 path is broken on the android-36 google_apis x86_64 image: camera1 works, camera2 characteristics fail with "Permission hard denied ... packageName <unknown>" (pm grant + appops both ineffective) |
| 2026-08-16 | Phase 3 state machine semantics (locked; do not revisit): `Idle` (taps no-op) → auto-arm via `BeginMeasure` on AR-view entry → `SelectingFirstPoint` (armed, no point) → `TapHit(p)` → `Measuring(start)` (live line + live distance) → `TapHit(p2)` → `Complete(start,end,distance)`; `Cancel`/`Reset` from any non-Idle state → `Idle` + detach measurement anchors; `TapMiss` always no-op; distance computed by an **injectable** `distanceFn` so JVM unit tests never touch FFI; Complete re-arms via a "Measure" button (Idle → SelectingFirstPoint) | Plan chain `Idle → SelectingFirstPoint → Measuring{start} → Complete{...}` has four states but no trigger for Idle→SelectingFirstPoint; auto-arm on view entry is the UX-correct trigger; gate 3.5 requires cancel → Idle literally |
| 2026-08-16 | Phase 3 anchors: create an `Anchor` (`hit.createAnchor()`) on each accepted hit; track them; detach on Cancel/Reset | Gate 3.5 requires "no dangling AR anchors leaking (check anchor count before/after)" — anchors give the check teeth; Phase 3 has no depth/feature-point sources (plane-only per plan) |
| 2026-08-16 | Phase 3 instrumented tests drive taps via `MainActivity.handleTapAt` (the exact code path the real `OnTouchListener` executes: pendingTap → GL-thread hitTest → anchor → reducer), not OS MotionEvent injection | A20 tracking is slow/finicky; OS-level injection is less deterministic. Both routes hit the same machine path. |
| 2026-08-16 | `Cancel` from `Complete` is a no-op (only `Reset` → Idle) — matches locked table; gate 3.5 tested via mid-measurement `Cancel` AND tap-tap-`Reset` | Table consistency; both anchor-cleanup paths covered in `ArMeasurementCancelTest` |
| 2026-08-18 | Camera-stream root cause: `BackgroundRenderer.draw` never initialized the quad's NDC corners before `frame.transformCoordinates2d(...)` — input was all-zeros → all 8 UVs returned 0.5 → the whole screen sampled ONE texel (flat dark/blue field). Present since Phase 2. Fix: set uvs to NDC corners (-1,-1..1,1) before the transform; diagnostic logs added (first-frame uvs, texture bind). The earlier GL_BLEND leak was real hygiene (fixed) but NOT the cause | Screencap evidence: pre-fix 55KB (flat), post-fix 566KB (real content); first-frame uvs 0.5→varied |
| 2026-08-18 | Camera texture rebinding is session- AND surface-aware: `textureNameSet` reset in `onSurfaceCreated` + `boundSessionId` (identity hash) guard — the old boolean guard never re-issued `setCameraTextureName` after EGL-surface/session recreation, leaving the camera bound to a dead texture | Surface recreation creates a new texture id in a new context; the stale guard made the background permanently black after any recreation |
| 2026-08-18 | Tap-point UX: screen-space reticle markers (green start, red end, white finger-follow preview) + `ACTION_MOVE` updates `lastTouch`; plane fill alpha 0.35→0.18; status/overlay text gets 60% black panels | User couldn't see where taps landed or whether the line matched object edges — gate 3.4 unexecutable |

## Agent Notes
- Generated symbols: `ping()` is a **top-level function in package
  `uniffi.core`** (JNA backend) — there is no `object Core` wrapper.
  Generated bindings live at `android/app/src/main/java/uniffi/core/core.kt`
  (single file; `distance(a: Point3, b: Point3): Float`, `Point3`,
  `Measurement`, `scoreConfidence`, `Confidence`, `MeasurementSource`).
- Rust core is single-file: `core/src/lib.rs` (all Phase 1 exports + tests).
- Build env vars: `ANDROID_HOME=/c/Android/Sdk`,
  `ANDROID_NDK_HOME=/c/Android/Sdk/ndk/28.2.13676358`,
  `JAVA_HOME=C:\Program Files\OpenJDK\jdk-25`. Full pins + sequence in
  `docs/setup.md`.
- uniffi crate + CLI version MUST match (both 0.32.0); CLI installed via
  `cargo install uniffi --version 0.32.0 --features cli` (binary `uniffi-bindgen`).
- **WHPX is available** — the emulator works. AVD `metiri` (API 36 google_apis
  x86_64, pixel_5) exists; boot headless with `-gpu swiftshader_indirect`.
  `avdmanager.bat` needs Windows-style `ANDROID_HOME` (msys paths find nothing).
  The Gate 0.3 ping test needs no ARCore; Phase 4+ does, and will need a
  physical device.
- `connectedDebugAndroidTest` targets whichever device is attached; the
  `metiri` AVD is the default when no physical device is plugged in.
- `.sisyphus/` is orchestrator runtime state — gitignored, not project content.
- The plan lives at `docs/plan.md` (moved from repo root during Phase 0).
- **Emulator camera2 is broken on android-36 google_apis x86_64** (2026 image):
  the camera service hard-denies camera2 permission attribution for our app
  (`packageName "<unknown>"`), camera1 legacy API works (built-in camera app
  opens device 10). Do NOT re-investigate; use a physical device for AR gates.
- **Physical device**: `RZ8MA00W9TN` = Samsung Galaxy A20 (SM-A205F), Android
  11, arm64-v8a. ARCore 1.53.0 arm64 sideloaded (the `_x86_for_emulator` APK is
  x86-only and silently fails to start its service on arm64 — use the plain
  `Google_Play_Services_for_AR_1.53.0.apk`). Screen PIN 2980; `svc power
  stayon true` + animations-off set for instrumented runs.
- **Phone**: `RZ8MA00W9TN` = Samsung Galaxy A20 (SM-A205F), Android 11,
  arm64-v8a. ARCore 1.53.0 arm64 sideloaded (the `_x86_for_emulator` APK is
  x86-only and silently fails to start its service on arm64 — use the plain
  `Google_Play_Services_for_AR_1.53.0.apk`). Screen PIN 2980; `svc power
  stayon true` + animations-off set for instrumented runs. Last REBOOTED
  2026-08-17 10:17 (cleared stale ARCore service state); app relaunched, then
  left idle — phone may now be asleep/disconnected. Re-apply: `adb devices -l`
  shows `RZ8MA00W9TN device`, then stayon + unlock dance (PIN 2980) before
  instrument runs.
- **Stationary never converges (verified 2026-08-17)**: after reboot + fresh
  launch, ~8 min of stationary warm-up stayed 100% `kNotTracking`, zero planes,
  while camera + VIO estimator were demonstrably active (feature-track
  timestamps advancing). Motion is MANDATORY for VIO init on the A20; the only
  known success is human-held active motion (Phase 2 planes 1→2→3).
- **Motion-diagnostic caveat**: `dumpsys sensorservice` "Samsung Linear
  Acceleration Sensor: last 30 events" shows a STALE buffer (wall timestamps
  don't advance) when no consumer is registered — NOT a live motion proof. For
  live motion during a run, read the LSM6DSL Accelerometer section (ARCore
  consumes it continuously) or watch VIO feature-track timestamps advance.
- A20 VIO: officially ARCore-supported (added Aug 2020) but the tracker can
  take minutes to leave initialization (`VisualInertialState is kNotTracking`)
  — needs motion + texture + decent light; it DID eventually track (planes
  1→2→3). Don't assume the device is broken on first try.
- **Phase 3 device-gate run list (TASK-017)** — install
  `android/app/build/outputs/apk/debug/app-debug.apk` +
  `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`,
  then per class: `adb -s RZ8MA00W9TN shell am instrument -w -e class <class>
  com.metiri.armeasure.test/androidx.test.runner.AndroidJUnitRunner`:
  1. `com.metiri.armeasure.CrossFfiDistanceTest` (3.3, no AR session — smoke test first),
  2. `com.metiri.armeasure.ArMeasurementTest` (3.2, needs a plane — be patient),
  3. `com.metiri.armeasure.ArMeasurementCancelTest` (3.5),
  4. `com.metiri.armeasure.ArSessionCloseTest` (follow-up A),
  5. `com.metiri.armeasure.ArSessionLifecycleTest` (2.2/2.4 regression).
  Capture each output under `/c/msys64/tmp/opencode/instr_phase3_*.txt`.
  One-command runner staged at `/c/msys64/tmp/opencode/run_gates_32_35.sh`
  (unlock PIN 2980 → install → run 3.2 + 3.5 → capture). **User must hold the
  phone MOVING during the run** (slow pans/arcs; stationary never converges).
- **GL_BLEND is a global context-state leak trap**: `PlaneRenderer.createOnGlThread`
  used to `glEnable(GL_BLEND)` once and never disable → camera background (drawn
  first) was blended `SRC_ALPHA` against the dark clear color, and the A20's OES
  camera texture alpha is 0 → background invisible (only plane triangles visible).
  Rule for all renderers: manage blend inside each pass/draw; never leave it
  enabled globally; opaque passes must disable blend (or force alpha 1 in shader).
  NOTE: this was real hygiene, but the ACTUAL camera-blank root cause was the UV
  bug below — do not confuse the two when reviewing history.
- **Camera-blank root cause (verified 2026-08-18, on-device)**: `BackgroundRenderer.draw`
  called `frame.transformCoordinates2d(OPENGL_NORMALIZED_DEVICE_COORDINATES, uvs, TEXTURE_NORMALIZED, uvs)`
  without ever populating `uvs` with the quad's NDC corners (-1,-1..1,1) — input
  was all zeros, so all 8 output UVs were 0.5 and the quad sampled a single
  center texel (flat dark/blue field). Symptom: "no camera stream" while planes
  render fine. Fix: init uvs to NDC corners before the transform. Diagnostics in
  place: `first-frame uvs` + `camera texture N bound to session M` logs; screencap
  size 55KB (flat) → 566KB (real content) after fix.
- **Plane stability on A20 (2026-08-18, on-device):** ARCore plane detection is intermittent — planes flicker 1→0→1, small fragments intersect/overlap, measurements can float off the surface. Tile test 33cm→32cm proves **core distance is accurate when a hit lands on a stable plane**; instability is tracker/plane-merging, not the Rust geometry. Mitigations for Phase 4 / hardening: (a) bright, textured light + slow motion helps VIO; (b) hit-test filtering (large horizontal-upward planes only, or add FeaturePoint fallback) vs current `Plane`-only; (c) UI: hide/show planes toggle, plane-merge debouncing.
- **Oracle hygiene carry-overs (non-blocking, Phase 3 review)** — apply before
  or during Phase 4: (1) `MainActivity.onDestroy` should call
  `detachAllMeasurementAnchors()` explicitly (session close covers it, but
  explicit is cleaner); (2) transient untracked-anchor window if `Cancel` races
  between GL-thread `createAnchor` and UI-thread `onTapHit` — no leak, detach is
  idempotent; (3) `pendingTap` not cleared in `onDestroy` — stale-tap risk on
  relaunch.
- Phase 3 implementation notes (from coder): `MeasureState.kt` is the pure
  reducer (`MeasureMachine.transition(state, action, distanceFn)`); hit filter is
  `trackable is Plane && trackingState == TRACKING`; live distance posts are
  epsilon-guarded (>1 mm); rejected-hit anchors are detached immediately.
- Phase 2 gate 2.3 evidence: `/c/msys64/tmp/opencode/phone_planes_found.png`
  (overlay `Planes: 3`), instrument outputs `instr_phone1.txt` /
  `instr_phone_final.txt` (`OK (2 tests)`), unit XML in
  `android/app/build/test-results/testDebugUnitTest/`.
- Emulator `metiri` AVD config was changed `hw.camera.front = none` →
  `emulated` during the camera2 investigation; emulator is currently KILLED.