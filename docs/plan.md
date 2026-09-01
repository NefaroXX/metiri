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
