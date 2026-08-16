# Metiri — AR Measure

## Active Context
- Phase 0 is complete and verified: all four gates pass. Gate 0.3 ran on the
  local emulator (AVD `metiri`, API 36) after WHPX turned out to be available;
  Gate 0.4 was proven via a scratch repo + `git clone` in
  `C:\msys64\tmp\opencode\metiri-clone` (build from a true clone of the
  would-be Phase 0 commit). Only the literal remote form remains: commit +
  push to Gitea, which is Sol's call.
- Blockers: none for Phase 0. Phase 1 can start once Sol commits/pushes and the
  remote-clone form of Gate 0.4 is confirmed.

## TODO Board
- [x] TASK-001: Amend plan (6 amendments) + move docs to `docs/`
- [x] TASK-002: Provision toolchain (Rust targets, cargo-ndk, uniffi CLI, JDK, Android SDK/NDK)
- [x] TASK-003: Phase 0 core scaffold + Gate 0.1 (`cargo test`/clippy clean)
- [x] TASK-004: Phase 0 Android scaffold + Gate 0.2 (`cargo ndk`, 3 ABIs)
- [x] TASK-005: App + androidTest APK build green (JNA dep added)
- [x] TASK-006: Gate 0.3 — instrumented ping test PASSED on emulator `metiri` (AVD)
- [ ] TASK-007: Commit + push Phase 0 to Gitea (Sol's call)
- [ ] TASK-008: Gate 0.4 — commit + push, then confirm remote-clone build (local `git clone` equivalent ALREADY PROVEN in `C:\msys64\tmp\opencode\metiri-clone`)
- [ ] TASK-009: Phase 1 — Rust geometry core (distance, confidence, enums, UniFFI round-trip)

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