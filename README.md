# AR Measure

An Android AR measurement app — point-to-point distance, rectangles, area, and
height, using ARCore's Depth API where available. Built with a Rust geometry
core (shared logic, testable in isolation) and a Kotlin/ARCore shell.

Not a straight iPhone Measure clone: the plan is to end up with a general
spatial-measurement engine (length → area → volume → saved projects) rather
than a single-purpose ruler, while keeping the actual MVP small and gated.

## Architecture

```
                 Android
                    │
          ┌─────────┴─────────┐
          │                   │
       Kotlin              Rust (core/)
          │                   │
      ARCore                Geometry
      Camera             Measurement
      Sensors              Filtering
      UI                  Confidence
      Storage             Algorithms
          │                   │
          └─────────┬─────────┘
                    │
                  UniFFI
```

- **`core/`** — Rust. Points, distance, confidence scoring, measurement
  validation. No Android/ARCore dependency — builds and tests standalone.
- **`android/`** — Kotlin. ARCore session management, camera, raycasting,
  rendering, UI, persistence trigger. Calls into `core/` via UniFFI-generated
  bindings.

Why split this way: ARCore's ecosystem is much easier to work with from
Kotlin/Java directly, so Rust is scoped to what it's actually good at —
geometry, numerical work, filtering — rather than trying to force the whole
app through FFI.

## Status

Early / pre-MVP. Being built in gated phases — see
[`docs/plan.md`](docs/plan.md) for the full phase breakdown and exit criteria.
Nothing merges past a phase boundary without its gate tests passing.

## Getting started

See [`docs/setup.md`](docs/setup.md) for the pinned toolchain and the exact
build sequence. Short version (set `ANDROID_HOME`, `ANDROID_NDK_HOME` and
`JAVA_HOME` first — see setup.md):

```bash
# Build Rust core for all Android ABIs + generate Kotlin bindings
# (run from the repo root — the workspace target/ dir is at the root)
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o android/app/src/main/jniLibs \
  build --release
uniffi-bindgen generate \
  --library target/aarch64-linux-android/release/libcore.so \
  --language kotlin \
  --out-dir android/app/src/main/java

# Build and test the Android app
cd android
./gradlew assembleDebug
./gradlew assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

Requirements: minSdkVersion 24 (ARCore "AR Required"), arm64-v8a build target
mandatory. The Kotlin bindings need the JNA runtime dependency (already in
`app/build.gradle.kts`). Details and rationale in `docs/setup.md`.

## Development workflow

- OpenCode implements against `docs/plan.md`, phase by phase.
- Claude reviews as architect/auditor — diff + test output per phase before
  merge, per the role boundary documented in the plan.
- Each phase has an explicit test gate; phases are not skippable and don't
  merge on self-assessment.

## Repo layout

```
ar-measure/
├── core/          # Rust geometry/measurement engine (UniFFI-exported)
├── android/        # Kotlin app (ARCore, camera, UI, storage)
├── xtask/          # Build orchestration helpers, if/when needed
└── docs/
    ├── plan.md      # Gated phase-by-phase implementation plan
    └── setup.md     # Toolchain setup and build sequence
```

## License

TBD.
