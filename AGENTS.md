# Metiri — AR Measure

## Active Context
- Phase 2 (ARCore session + plane detection) is COMPLETE: all four gates pass on
  the physical device (Samsung Galaxy A20 SM-A205F, Android 11). Gate 2.1 unit
  5/5; gates 2.2 + 2.4 instrumented `OK (2 tests)`; gate 2.3 manual checkpoint
  demonstrated — planes tracked 1→2→3 live, overlay `Planes: 3`, screencap saved
  (`/c/msys64/tmp/opencode/phone_planes_found.png`).
- The android-36 google_apis x86_64 emulator's camera2 path is broken for our
  app (camera1 works, camera2 characteristics fail with "Permission hard denied
  ... packageName <unknown>" even with pm grant + appops) — do not re-investigate.
- Next: commit + push Phase 2 (in progress), then Phase 3 (raycast + point
  selection → wire into Rust core). Phase 3 needs the Phase 2 audit sign-off.

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
- [x] TASK-012: Commit + push Phase 2 (commit + push in progress)
- [ ] TASK-013: Phase 3 — raycast + point selection → wire into Rust core (starts after Phase 2 audit sign-off)

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

## Agent Notes
- Generated symbols: `ping()` is a **top-level function in package
  `uniffi.core`** (JNA backend) — there is no `object Core` wrapper.
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
- A20 VIO: officially ARCore-supported (added Aug 2020) but the tracker can
  take minutes to leave initialization (`VisualInertialState is kNotTracking`)
  — needs motion + texture + decent light; it DID eventually track (planes
  1→2→3). Don't assume the device is broken on first try.
- Phase 2 gate 2.3 evidence: `/c/msys64/tmp/opencode/phone_planes_found.png`
  (overlay `Planes: 3`), instrument outputs `instr_phone1.txt` /
  `instr_phone_final.txt` (`OK (2 tests)`), unit XML in
  `android/app/build/test-results/testDebugUnitTest/`.
- Emulator `metiri` AVD config was changed `hw.camera.front = none` →
  `emulated` during the camera2 investigation; emulator is currently KILLED.