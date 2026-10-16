# Metiri — Revised Plan (WIP — 2026-09-01)

> **Status: WIP — pending oracle revisions (failure strategy, ARCore VIO role, MVP cut). Do not treat as final.**
>
> This document replaces the previous gated ARCore-plane plan. The old plan is
> archived at `docs/plan.prev.md` for reference.
>
> **Revision notes — open items flagged by oracle review:**
> - **Missing failure strategy**: What happens when no acquisition layer can produce a
>   valid point? Needs explicit degrade/fallback UX, not just silent failure.
> - **Premature SpatialProvider abstraction**: The `SpatialProvider` trait is designed
>   before any provider is implemented. Risk of over-engineering. Consider deferring
>   the abstraction until ≥2 providers exist.
> - **10C depth probe uncertain**: Using the iPhone 10C's second (telephoto) camera for
>   stereo depth is unproven. ARKit's dual-camera API is not directly available on
>   Android. Needs proof-of-concept before commitment.
> - **Monocular ML depth estimation**: Listed as a possible acquisition layer but is
>   speculative — no production ML depth model runs real-time on A20-class hardware
>   today. Treat as research spike, not implementation target.

---

## 1. Target UX

The app measures real-world distances by tapping two points in 3D space. The user
holds the phone, sees the camera feed, taps point A, taps point B, and gets a
distance in meters with a confidence indicator.

**Core interaction loop:**
1. Open app → camera feed with reticle
2. Tap first point → marker appears (green)
3. Tap second point → line drawn, distance displayed
4. Tap "Measure" again → resets for next measurement

**What changes from the old plan:**
- Measurement is no longer gated on ARCore plane detection. Points can be placed
  anywhere in 3D space — on a wall, in mid-air anchored to a visual feature, on
  a floor without a detected plane.
- The system maintains a persistent world map so measurements survive session restarts
  and points can be re-referenced.
- ARCore is one input source, not the system backbone.

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────┐
│                        Kotlin Shell                        │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │  AR Renderer  │  │  Tap Handler  │  │   UI Layer   │   │
│  │  (GL Surface) │  │  (hit-test)   │  │  (markers,   │   │
│  │               │  │               │  │   labels)    │   │
│  └──────┬───────┘  └──────┬───────┘  └──────────────┘   │
│         │                  │                               │
│  ┌──────┴──────────────────┴───────────────────────────┐  │
│  │              SpatialProvider (trait)                  │  │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────┐  │  │
│  │  │ ARCore  │ │ Stereo  │ │  Depth  │ │ Monocular│  │  │
│  │  │ Provider│ │ Provider│ │ Fusion  │ │    ML    │  │  │
│  │  └─────────┘ └─────────┘ └─────────┘ └──────────┘  │  │
│  └──────────────────────┬──────────────────────────────┘  │
│                         │                                   │
│  ┌──────────────────────┴──────────────────────────────┐  │
│  │           Persistent World Map (Rust)                 │  │
│  │  Points, descriptors, pose graph, session history     │  │
│  └──────────────────────┬──────────────────────────────┘  │
└─────────────────────────┼────────────────────────────────┘
                          │ UniFFI
┌─────────────────────────┴────────────────────────────────┐
│                    Measurement Engine (Rust)               │
│  distance(), confidence scoring, point management,        │
│  world-map queries, persistence (SQLite)                  │
└──────────────────────────────────────────────────────────┘
```

**Key principle:** The Kotlin shell handles rendering, input, and AR-session
lifecycle. The Rust core owns the world model, measurement logic, and
persistence. The `SpatialProvider` trait bridges AR-session data into the world
map.

---

## 3. Exploit 10C Second Camera

The Samsung Galaxy A20 (SM-A205F) has a single rear camera. However, the target
device ecosystem includes phones with dual cameras (e.g., iPhone 10C with
wide + telephoto). On such devices:

- Use the dual-camera pair for stereo depth estimation — compute depth from the
  baseline offset between the two lenses
- This gives hardware depth without requiring a ToF/structured-light sensor
- The depth map feeds into the Persistent World Map as a high-confidence point source

**Caveat (oracle flag):** ARKit's `ARFaceTrackingConfiguration` exposes both cameras,
but Android's Camera2 API does not guarantee simultaneous output from both lenses.
This needs a proof-of-concept before committing to it as a primary path. For the
A20 (single camera), this layer is unavailable — the system must work without it.

---

## 4. Fallbacks

The system degrades gracefully through acquisition layers:

| Priority | Layer | Hardware Required | Accuracy | A20 Support |
|----------|-------|-------------------|----------|-------------|
| 1 | Hardware Depth (ToF/structured light) | Depth sensor | Highest | No |
| 2 | Stereo Depth | Dual cameras | High | No |
| 3 | ARCore + VIO | ARCore-supported device | Medium | Yes |
| 4 | Monocular ML depth | None (CPU/GPU inference) | Low–Medium | Speculative |
| 5 | User-assisted (known-distance calibration) | None | Variable | Yes |

**Fallback chain:** When a higher-priority layer fails or is unavailable, the
system drops to the next. Each measurement records which source provided the
depth, feeding into confidence scoring.

**A20-specific path:** On the A20, the primary path is ARCore VIO (layer 3).
Layer 4 (monocular ML) is a research spike. Layer 5 (user-assisted) is the
last-resort MVP.

---

## 5. A20 World Model

The A20's ARCore VIO is the primary spatial input on this device. Key
observations from Phase 2–3 testing:

- **Motion is mandatory.** Stationary warm-up stays at `kNotTracking` indefinitely.
  The user must actively move the phone for VIO to initialize.
- **Planes are intermittent.** Flicker 1→0→1, fragments intersect/overlap,
  measurements float. The plane detection itself is not the measurement
  system — it's one possible hit-test source.
- **Core distance is accurate.** The tile test (33cm → 32cm, −3%) proves the
  Rust geometry works when a hit lands on a stable surface.
- **VIO takes minutes to initialize** on the A20. Needs motion + texture + light.

**Strategy:** Don't fight ARCore's plane instability. Instead:
1. Use ARCore VIO for camera pose tracking (it's good at this)
2. Use ARCore hit-test as one spatial provider, but not the only one
3. Anchor points to visual features, not planes
4. Fuse multiple observations of the same point over time to improve accuracy

---

## 6. Persistent Points

Instead of ephemeral plane-based measurements, the system maintains a persistent
world map of 3D points:

**Point lifecycle:**
1. **Observed** — A point is first detected (via any acquisition layer)
2. **Tracked** — The point is re-observed across frames, improving its position estimate
3. **Confirmed** — The point has enough observations to be reliable
4. **Measured** — The user has tapped this point as a measurement endpoint

**Persistence:**
- Points survive app restarts via SQLite (Rust core, `rusqlite`)
- Each point stores: 3D position, descriptor (for re-identification), observation
  count, confidence, creation timestamp, source layer
- On relaunch, the system attempts to re-localize existing points against the
  current camera view

**Benefit:** A measurement taken today can be verified tomorrow. Points placed on
a room's corners form a reusable spatial reference.

---

## 7. Depth Fusion

Multiple depth observations of the same 3D location are fused to improve accuracy:

- **Temporal fusion:** Repeated observations of the same point across frames are
  averaged (weighted by per-observation confidence)
- **Cross-source fusion:** A point observed by both ARCore hit-test and stereo
  depth gets a fused estimate with higher confidence
- **Outlier rejection:** Observations that deviate significantly from the running
  estimate are discarded (protects against tracking glitches)

The fusion runs in the Rust core. It's a simple weighted average with outlier
filtering — not a full SLAM back-end. The goal is accuracy improvement, not
simultaneous localization and mapping.

---

## 8. SpatialProvider Abstraction

A `SpatialProvider` trait in Kotlin (called from Rust via UniFFI) abstracts the
source of 3D spatial data:

```kotlin
interface SpatialProvider {
    val name: String
    val isAvailable: Boolean
    fun observePoint(screenX: Float, screenY: Float): Point3?
    fun getCameraPose(): Pose?
    fun getTrackingQuality(): TrackingQuality
}
```

**Implementations:**
- `ArCoreProvider` — wraps ARCore hit-test + tracking state
- `StereoProvider` — dual-camera depth (future, device-dependent)
- `DepthSensorProvider` — ToF/structured-light (future, device-dependent)
- `MonocularMlProvider` — ML depth estimation (research spike)

**Oracle flag:** This abstraction is designed before any provider beyond ARCore
is implemented. Consider deferring the trait until a second provider exists.
For now, the ARCore path can be called directly.

---

## 9. Development Order

The revised plan builds bottom-up, starting from what's proven:

### Phase R1 — Persistent Point Core (Rust)
- Point struct with position, descriptor, observation history
- Temporal fusion (weighted average, outlier rejection)
- SQLite persistence for the world map
- **Gate:** Unit tests for fusion accuracy, round-trip persistence

### Phase R2 — ARCore VIO Provider
- Use ARCore for camera pose tracking only (not planes)
- Tap → ARCore hit-test → point observation → feed into world map
- Refine the existing state machine to work with persistent points
- **Gate:** Two taps produce a measurement; points survive app restart

### Phase R3 — Point Re-identification
- Visual descriptor matching for re-localizing points across sessions
- Camera relaunch → match existing points to current view
- **Gate:** Place points, restart app, confirm points are re-found

### Phase R4 — Confidence + Validation
- Confidence scoring using fused observation data
- Tracking-quality gating (reject when VIO is unreliable)
- Source-layer attribution in the measurement record
- **Gate:** Confidence tiers match expected behavior; rejected taps are handled

### Phase R5 — Advanced Depth (if device available)
- Stereo depth on dual-camera devices
- Hardware depth on ToF/structured-light devices
- Cross-source fusion with ARCore observations
- **Gate:** Depth-sourced measurements are equal or more accurate than ARCore-only

### Phase R6 — Monocular ML Research Spike
- Evaluate ML depth estimation models for real-time performance on A20
- If viable: integrate as an additional acquisition layer
- If not viable: document findings and remove from plan
- **Gate:** Frame-rate benchmark on A20; accuracy comparison against ARCore

### Phase R7 — Polish + Hardening
- UI refinement (markers, line rendering, measurement history)
- Error states and user feedback
- Performance optimization
- **Gate:** End-to-end UX walkthrough on A20

---

## 10. What We're Keeping from the Old Plan

The following from the previous plan (Phases 0–3) is preserved and reusable:

- **Rust core** (`core/src/lib.rs`) — `Point3`, `distance()`, `Confidence`,
  `Measurement`, `scoreConfidence()` — all proven, all green
- **UniFFI bindings** — JNA backend, toolchain pins, build pipeline
- **Android scaffold** — Gradle config, ABIs, `jniLibs` wiring
- **State machine** — `MeasureState.kt` sealed class, `MeasureMachine.transition()`
  — the transition logic is sound; it needs re-wiring to persistent points
- **Camera rendering** — `BackgroundRenderer`, `PlaneRenderer`, `LineRenderer`,
  `MarkerRenderer` — the GL pipeline works
- **Tap handling** — `MainActivity.handleTapAt` path, `pendingTap` → hit-test
  → anchor → reducer — the plumbing is proven
- **Instrumented test harness** — device gate runner, APK build, `adb instrument`

---

## 11. What We're Changing

| Old Plan | Revised Plan |
|----------|-------------|
| ARCore plane detection = measurement source | ARCore VIO = camera pose tracker; planes = one hit-test option among many |
| Ephemeral measurements (session-scoped) | Persistent world map (survives restarts) |
| Single acquisition layer (ARCore Depth API) | Multiple layers with fallback chain |
| Phase 5: Depth API as add-on | Depth is a first-class acquisition layer from Phase R1 |
| Measurement = two taps on a plane | Measurement = two persistent points in 3D space |

---

## 12. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| A20 VIO too slow/unreliable for real-time tracking | Medium | High | Fallback to user-assisted measurement; A20 may not be the target device for v1 |
| 10C stereo depth unworkable on Android | High | Low | Treat as bonus; not in MVP critical path |
| Monocular ML too slow on A20 | High | Medium | Research spike only; don't commit resources until proven |
| Persistent point re-identification fails across sessions | Medium | High | Visual descriptors need tuning; fallback to fresh-session-only mode |
| `SpatialProvider` abstraction premature | Medium | Low | Can inline ARCore calls until second provider exists |
| SQLite schema migration pain | Low | Medium | Phase R1 starts with the schema; migration pattern established early |

---

## 13. Success Criteria

**MVP (Phase R1–R2):**
- Tap two points → get a distance with confidence indicator
- Measurement is accurate within ±5% for distances 0.5m–5m
- Points persist across app restarts
- Works on Samsung Galaxy A20 (SM-A205F)

**Full Target (Phase R1–R5):**
- Works on any ARCore-supported Android device
- Dual-camera and depth-sensor devices get improved accuracy
- Confidence scoring reflects actual measurement quality
- World map survives multiple sessions and is re-localizable

---

## 14. Open Questions

1. **A20 as target device?** The A20's VIO is slow and planes are unstable.
   Should we target a more capable device for v1 and treat A20 as a
   stretch goal?
2. **Visual descriptor format?** ARCore's `AugmentedImage` database vs.
   ORB/SIFT features vs. learned descriptors. Needs research.
3. **World map size limits?** How many points can we store before SQLite
   queries slow down? What's the pruning strategy?
4. **Offline operation?** Does the app need to work without network, or is
   cloud-assisted depth/SLAM acceptable?
5. **Multi-device sync?** Is sharing a world map between two phones in scope?

---

---

## Previous Plan (Pre-Revision) — for reference

> The original gated plan (Phases 0–6, ARCore-plane-centric) is archived at
> **`docs/plan.prev.md`**. Phases 0–3 were completed and committed
> (`a25ffee` through `188c9bf`). Phases 4–6 were never started.
>
> Key takeaways carried forward:
> - Rust core, UniFFI bindings, and the build pipeline are proven and reusable
> - The state machine architecture is sound (Kotlin shell, Rust geometry)
> - ARCore plane detection works but is too intermittent to be the measurement
>   backbone — this is why we pivoted to persistent 3D world points
> - The A20's camera stream was fixed (NDC UV bug) and is working correctly

---

## 15. Phase 4 — Dual-Provider Measurement (redefined 2026-09-22)

> **Status: authoritative as of 2026-09-22 (decision approved by Sol).** This
> section redefines Phase 4 (original plan numbering: Phases 0–3 completed,
> Phases 4–6 never started) as **dual-provider measurement**, gated by 4.1–4.6.
> Every section above is historical and remains intact — nothing here rewrites
> them. Where this conflicts with the near-term ordering of the revised-plan
> phases in section 9 (R1–R7), Phase 4 is the concrete next implementation and
> takes precedence; the R-phases remain the longer-term R&D roadmap.

### 15.1 Why the redefinition

- **Planes are broken on the test device.** ARCore plane detection on the Galaxy
  A20 is intermittent — flicker 1→0→1, intersecting/overlapping fragments,
  measurements can float — verified on-device 2026-08-18.
- **Pose tracking is good.** The tile calibration (33 cm → 32 cm, −3%) proves the
  VIO pose + Rust distance pipeline is accurate when a hit lands on a stable
  surface.
- **Conclusion:** measurable accuracy does not depend on plane output. Planes are
  demoted to a debug overlay only; **no gate may depend on plane output**.
- **Device-surface reality:** the publish rule (section 15.4) requires the app to
  work on ARCore-*certified* and ARCore-*uncertified* devices. That forces a
  second, sensor-only provider.

> Naming note (discrepancy with earlier sections): the oracle revision in
> sections 3 and 12 referred to a dual-camera "iPhone 10C" using stereo depth.
> The actual second test device is the **Redmi 10C** (Snapdragon 680,
> ARCore-*uncertified*). The stereo-depth exploit (section 3) does not apply to
> it and is not part of Phase 4; Phase 4's Provider 2 is sensor-only. This note
> documents the discrepancy; the earlier sections are preserved as history.

### 15.2 Architecture: dual-provider

Both providers implement the `SpatialProvider` interface already sketched in
section 8 (`name`, `isAvailable`, `observePoint`, `getCameraPose`,
`getTrackingQuality`) and feed `Point3` into the same `MeasureState` machine and
Rust measurement core.

**Provider 1 — ARCore without planes (certified devices, e.g. A20):**
- Hit-test sources, in priority order: depth hits first, feature-point hits second.
- `Config.PlaneFindingMode.DISABLED` — planes are never a measurement source.
- `Config.DepthMode.AUTOMATIC` only when `isDepthModeSupported(AUTOMATIC)`, else
  `DISABLED` (matches the existing decision in AGENTS.md).
- Planes may be rendered as a debug overlay only, with a toggle; never a gate
  dependency.

> **Amended 2026-09-22 (see 15.7):** the probe found the A20 does **not** support
> the Depth API, so the "depth hits first" tier above is **not available on the
> test device** and Provider 1 runs on feature-point hits alone. `DepthMode` is
> therefore `DISABLED` in practice; the `isDepthModeSupported` guard stays in place
> so the depth tier lights up on any future depth-capable device without a code
> change.

**Provider 2 — sensor-only (uncertified devices, e.g. Redmi 10C):**
- Zero `com.google.ar.core` imports; no ARCore session is started.
- Inputs: camera (CameraX preview) + accelerometer trig — ground-span;
  pitch-triangulation for vertical measurements; step-counter walk for
  stride-based distance.
- Adds **one** `MeasurementSource` variant in the Rust core for sensor-derived
  points; everything downstream (state machine, distance, confidence) is shared.

### 15.3 Manifest change

- `com.google.ar.core` feature: **required → optional**
  (`<uses-feature android:required="false">`).
- Result: a single universal APK that installs and runs on certified and
  uncertified devices alike. `minSdk 24` unchanged.

### 15.4 Publish rule (hard acceptance)

The app must work on the Galaxy A20, the Redmi 10C, **and a handful of friends'
devices** — or it is not published. This is the acceptance bar for Phase 4's end
state (gate 4.5).

### 15.5 Gates 4.1–4.6

| Gate | Scope | Pass criteria |
|------|-------|---------------|
| **4.1** | Depth probe on A20 (incl. frame-rate impact) — **probe DONE 2026-09-22 (see 15.7): the A20 does NOT support the Depth API, so "depth-capable pipeline" is unachievable as originally worded** | **RESOLVED:** selected pipeline = **feature-point hits** (no depth tier available on the A20); `DepthMode` stays `DISABLED`; frame-rate criterion **still applies** — **no performance regression >15%** |
| **4.2** | Plane-free ARCore pipeline | `nearestHit` re-spec dropping plane tiers; config changes (`PlaneFindingMode.DISABLED`, guarded `DepthMode`); debug plane-overlay toggle; **A20 tile test ≤5% error**; **zero floating measurements across 3 runs**; JVM green |
| **4.3** | Sensor provider module | Trig helpers in Rust core + CameraX preview + calibration flow; **10C tile test ≤8% uncalibrated / ≤5% calibrated, per mode**; **ARCore path never invoked** on 10C |
| **4.4** | `SpatialProvider` + selection UX | Runtime auto-select + manual override + diagnostics screen + manifest flip to optional; **correct provider on both A20 and 10C**; unknown device triaged with no adb |
| **4.5** | Friends' device trials | **3+ units**; diagnostics + tile test each; **correct provider + accuracy within the tier band** |
| **4.6** | Audit → commit + push | Diff review against Phase 3–4 gates; evidence captured; commit + push |

### 15.6 Provider-1 pose baseline

The Phase 3 gate 3.4 result (TASK-018) — **tile 33 cm → app 32 cm (−3%)** —
doubles as the Provider-1 pose baseline evidence behind gate 4.2's ≤5% bar.

### 15.7 Probe outcomes (2026-09-22) — branch conditions closed, pipelines locked

> Reported by Sol from on-device probes on 2026-09-22. These results close the
> four open branch conditions named in gates 4.1 and 4.3 and **lock the pipeline
> choices** for gates 4.2 and 4.3. Sections 15.1–15.6 are preserved as the
> pre-probe record; this section is the resolution. Where the two disagree, this
> section wins.

| # | Probe | Device | Result | Consequence for Phase 4 |
|---|-------|--------|--------|--------------------------|
| 1 | Depth API support | Galaxy A20 (SM-A205F) | **Not supported** | Depth-first hit tier **abandoned**. Provider 1 = **feature-point hits**. `PlaneFindingMode.DISABLED` unchanged; `DepthMode` stays `DISABLED` in practice |
| 2 | Gyroscope presence | Redmi 10C | **Effectively absent** — AIDA64 lists no gyro; only the SC7A20 accelerometer, whose x/y/z tilt response is expected accel-only behavior | Provider 2 derives pitch from **static accelerometer tilt only**; **no live rotation-vector/heading tracking** → **aim-and-tap** interaction |
| 3 | Camera2 stream count | Redmi 10C | **1 front + 1 back visible to apps**; the aux 2MP depth camera is **not exposed** | **Stereo-depth path closed empirically** on the 10C (not merely unproven). Supersedes the "unproven" flag in sections 3 and 12 |
| 4 | Step sensors | Redmi 10C | **Present** — Qualcomm step detector + step counter | **Walk-off mode via `TYPE_STEP_COUNTER` is viable**, gated on **stride calibration** |

#### 15.7.1 Provider 1 (A20) — locked pipeline

- Hit-test source: **feature points** (`Frame.hitTest` filtered to
  `Trackable is FeaturePoint`), with pose from VIO.
- `Config.PlaneFindingMode.DISABLED` — planes stay a debug overlay only, never a
  measurement source, never a gate dependency.
- `Config.DepthMode` resolves to `DISABLED` on this device; the
  `isDepthModeSupported(AUTOMATIC)` guard is retained so the depth tier activates
  automatically on any future depth-capable device.
- **Known limitation (accepted):** feature points require texture. On a **blank or
  untextured wall there is no feature point to hit**, so Provider 1 cannot measure
  there. This is a documented product limitation with explicit "no point here"
  feedback — not a reason to re-introduce a plane or depth tier.
- Practical guidance for the 4.2 / 4.5 tile tests: textured surface, decent light,
  slow motion (VIO still refuses to initialize when stationary — see sections 5
  and 15.6).

#### 15.7.2 Provider 2 (10C) — locked pipeline

- **Sensor set, as probed:** accelerometer (SC7A20) + camera + step
  detector/counter. No gyro. No usable depth camera.
- **Aim mode (vertical + ground-span):** pitch is read from the **static
  accelerometer tilt** (gravity vector). There is no live orientation tracking, so
  the interaction is **aim-and-tap** — aim the phone, hold still for the pitch
  sample to settle, then tap. The calibration flow and the ≤8% / ≤5% per-mode
  bands in gate 4.3 are unchanged by this.
- **Walk mode:** `TYPE_STEP_COUNTER` (Qualcomm) for stride-based distance. Needs
  **stride calibration**, plus the `android.permission.ACTIVITY_RECOGNITION` runtime
  grant on API 29+ (the 10C ships Android 11).
- **Dropped:** stereo depth (probe 3). The `StereoProvider` in section 8 and the
  stereo rows in sections 3, 4 and 12 remain as historical design notes; they are
  out of Phase 4 scope and are not implementable on either test device.
- Still zero `com.google.ar.core` imports and no ARCore session on this path.

#### 15.7.3 Open items carried into 4.2 / 4.3

1. Confirm at implementation time whether the 10C exposes
   `TYPE_ROTATION_VECTOR` via its sensor HAL. Android can synthesize this from the
   accelerometer (and magnetometer for heading) even without a gyro; if present it
   may improve pitch stability over raw accel, though heading quality is
   unverified. Until checked, assume accel-only.
2. Define the stride-calibration UX for walk mode (height input vs. a measured
   calibration walk) and pin which variant gate 4.3's ≤5% band is judged against.
3. Decide the blank-wall degrade UX for Provider 1 (15.7.1) before 4.2's tile
   test, so the ≤5% criterion is only claimed for textured surfaces.
4. Gate 4.5 (friends' devices) should record, per device, which of the four probe
   conditions hold. A friend device with a gyro or an exposed second camera could
   justify a provider variant later, but that is out of Phase 4 scope.
