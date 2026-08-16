# AR Measure — Toolchain Setup (Phase 0)

This is the doc Gate 0.4 requires: a clean clone must build using exactly these
steps, no undocumented manual fixes. If OpenCode has to do something not written
here to get a clean build working, that step gets added here before the gate
counts as passed.

## Confirmed current requirements (verified, not assumed)

- **minSdkVersion 24** — ARCore "AR Required" apps must declare API level ≥ 24.
  (We're AR Required, not AR Optional — there's no non-AR fallback mode planned.)
- **arm64-v8a is mandatory.** Google Play Services for AR dropped support for
  32-bit-only apps on 64-bit devices; a build missing an arm64-v8a `.so` will
  fail to start an AR session on affected devices even if it installs fine.
  armeabi-v7a can be included for older 32-bit-only devices but arm64-v8a is
  non-negotiable.
- **Depth API device coverage is high but not universal** — over 88% of active
  devices as of the ARCore device catalog, but Phase 2's capability-check screen
  still needs to handle the non-supported case, don't assume it away.
- OpenGL ES 3.0 minimum, 3.2 on most devices — irrelevant to Phase 0/1 but noted
  for whoever hits the renderer phase.

Re-verify these against `developers.google.com/ar/devices` and
`developers.google.com/ar/develop` if more than ~2 months have passed since this
doc was written — ARCore device support and API surface shift.

## Prerequisites

1. **Rust toolchain**: stable, via rustup. Add Android targets:
   ```
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   ```
   (x86_64 target is for emulator builds — Gate 0.3's instrumented test will
   likely run on an x86_64 emulator unless a physical device is used.)

2. **Android NDK**: install via Android Studio SDK Manager, not standalone.
   Pin the exact NDK version in `local.properties` / `build.gradle` — do not
   float on "latest," ARCore + cargo-ndk + NDK version mismatches are a known
   source of silent breakage. Record the exact version installed here once
   chosen.

3. **cargo-ndk**:
   ```
   cargo install cargo-ndk
   ```

4. **UniFFI**: added as a Cargo dependency in `core/Cargo.toml`, plus the
   `uniffi-bindgen` binary — per the UniFFI Gradle integration docs, keep
   `uniffi-bindgen` in its own crate/binary target so iteration doesn't require
   a full rebuild of `core` just to regenerate Kotlin bindings.

5. **Android Studio**: current stable channel. Kotlin version compatible with
   the UniFFI Kotlin Multiplatform bindings generator in use — check
   compatibility table before bumping Kotlin version later in the project.

## Build sequence (this is what Gate 0.4 actually tests)

```bash
# 1. Build the Rust core for all target Android ABIs
cd core
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ../android/app/src/main/jniLibs \
  build --release

# 2. Generate Kotlin bindings from the Rust library
cargo run --bin uniffi-bindgen generate \
  --library target/aarch64-linux-android/release/libcore.so \
  --language kotlin \
  --out-dir ../android/app/src/main/java

# 3. Build and run the Android app
cd ../android
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest   # Gate 0.3's instrumented test
```

Exact `uniffi-bindgen` invocation syntax depends on the UniFFI version pinned
in `core/Cargo.toml` — confirm against that version's docs rather than copying
this verbatim if the dependency version changes.

## What "clean clone" means for Gate 0.4

```bash
git clone <repo-url> /tmp/ar-measure-verify
cd /tmp/ar-measure-verify
# run the build sequence above, start to finish
# zero manual edits, zero "oh I also had to..." steps
```

If this fails, Gate 0.4 fails — fix the setup doc or the build config, not the
test. The whole point of this gate is catching FFI toolchain drift before any
real logic exists to blame it on.

## Known risk areas to watch (from prior research, not yet hit in practice)

- **JNA/JNI callback GC pinning**: irrelevant until Phase 4's tracking-quality
  polling — but if anyone is tempted to switch from poll-based to callback-based
  updates later, re-read why that was rejected (Kotlin GC can collect a callback
  mid-flight unless explicitly pinned; poll-based sidesteps this entirely).
- **Enum round-tripping across FFI**: this is explicitly tested in Gate 1.6 —
  UniFFI generally handles this well but it's the one place past projects have
  reported subtle bugs (mismatched variant ordering between Rust and generated
  Kotlin if the enum is hand-edited on either side after generation).
