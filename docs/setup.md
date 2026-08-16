# AR Measure — Toolchain Setup (Phase 0)

This is the doc Gate 0.4 requires: a clean clone must build using exactly these
steps, no undocumented manual fixes. If anything has to be done that is not
written here to get a clean build working, that step gets added here before the
gate counts as passed.

## Pinned toolchain (verified 2026-08-16 — Phase 0 deliverable)

Do not float versions. Every pin below was chosen deliberately; see notes.

| Component | Pinned version | Notes |
|---|---|---|
| Rust | 1.95.0 (edition 2024) | via rustup; Android targets added via `rustup target add` |
| uniffi (crate) | **0.32.0** | `core/Cargo.toml` uses `uniffi = "=0.32.0"` |
| uniffi bindgen CLI | **0.32.0** | MUST match the crate. Install: `cargo install uniffi --version 0.32.0 --features cli` (crate `uniffi_bindgen`, binary `uniffi-bindgen`) |
| cargo-ndk | 4.1.2 | `cargo install cargo-ndk --locked` |
| JDK | 25 (OpenJDK, `C:\Program Files\OpenJDK\jdk-25`) | Gradle 9.1+ runs on Java 25; no JDK 21 needed |
| Gradle (wrapper) | 9.3.1 | `gradle-wrapper.properties` |
| AGP | 9.1.1 | Kotlin support is **built into AGP 9** — do NOT apply `org.jetbrains.kotlin.android` (AGP 9 rejects it) |
| compileSdk / targetSdk | 36 | AGP 9.1 supports up to API 37; 36 is current stable |
| minSdk | 24 | ARCore "AR Required" floor (see below) |
| Build Tools | 36.0.0 | AGP default |
| NDK | 28.2.13676358 (r28c) | AGP default; see env vars below |
| platform-tools | 37.0.1 | adb |
| JNA | 5.19.1 (`@aar`) | **Required** — the UniFFI-generated Kotlin bindings use the JNA backend. Android needs the **AAR variant** (`implementation("net.java.dev.jna:jna:5.19.1@aar")`): it ships `libjnidispatch.so` per ABI. The plain jar has no Android natives → `UnsatisfiedLinkError` at runtime (caught by Gate 0.3, 2026-08-16) |
| androidx.core:core-ktx | 1.18.0 | |
| androidx.test:core / runner | 1.7.0 | instrumented tests |
| androidx.test.ext:junit | 1.3.0 | instrumented tests |

### Why these versions (so the next person doesn't "just bump")

- **AGP 9.1.1 + Gradle 9.3.1**: Kotlin 2.4.x lists max *fully supported* AGP as
  9.1.0 (kotlinlang.org compatibility table). AGP 9.2/9.3 work but ride the edge
  of the matrix. With built-in Kotlin, there is no separate KGP version to pin —
  AGP 9.1.1 bundles its own Kotlin compiler.
- **JDK 25**: Gradle supports running on Java 25 since 9.1.0. The existing
  install at `C:\Program Files\OpenJDK\jdk-25` is used directly.
- **uniffi 0.32.0**: current stable; crate + CLI must be the same version or
  bindgen generation fails on metadata version checks.

## Environment variables (required for every build shell)

```bash
export ANDROID_HOME=/c/Android/Sdk
export ANDROID_NDK_HOME=/c/Android/Sdk/ndk/28.2.13676358   # cargo-ndk will NOT auto-detect
export JAVA_HOME="C:\\Program Files\\OpenJDK\\jdk-25"
```

The SDK lives at `C:\Android\Sdk` (installed via cmdline-tools + sdkmanager —
no Android Studio). `ANDROID_NDK_HOME` is mandatory because cargo-ndk's
auto-detection only looks at Android Studio's default SDK location, which this
machine does not use. Gradle reads `android/local.properties` (`sdk.dir`) so it
does not need `ANDROID_HOME` in env.

## Prerequisites (one-time)

1. **Rust targets** (already added):
   ```
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   ```
2. **cargo-ndk** (already installed): `cargo install cargo-ndk --locked`
3. **uniffi-bindgen CLI** (already installed):
   `cargo install uniffi --version 0.32.0 --features cli`
4. **Android SDK components** (already installed under `C:\Android\Sdk`):
   ```
   sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358"
   ```
   cmdline-tools were bootstrapped from `commandlinetools-win-11076708_latest.zip`.

## Build sequence (this is what Gate 0.4 actually tests)

> **Run every step from the repo root.** The workspace `target/` dir lives at
> the root (not `core/target/`), and `uniffi-bindgen --library` resolves
> relative to the current directory — a `cd core` before step 2 breaks the
> path (caught by the Gate 0.4 clean-copy simulation, 2026-08-16).

```bash
# 1. Build the Rust core for all target Android ABIs -> jniLibs
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o android/app/src/main/jniLibs \
  build --release

# 2. Generate Kotlin bindings from the built library (library mode)
uniffi-bindgen generate \
  --library target/aarch64-linux-android/release/libcore.so \
  --language kotlin \
  --out-dir android/app/src/main/java
#   ^ emits uniffi/core/core.kt (package uniffi.core). ktlint warning here is
#     cosmetic only; --no-format suppresses it.

# 3. Build and test the Android app
cd android
./gradlew assembleDebug
./gradlew assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest   # Gate 0.3's instrumented test — needs a device or the `metiri` AVD
```

Both Rust and Gradle builds must run with the env vars from above exported.
On a fresh clone, `android/local.properties` does not exist (gitignored);
exporting `ANDROID_HOME` is sufficient — AGP falls back to it when
`local.properties` is absent.

## What "clean clone" means for Gate 0.4

```bash
git clone <repo-url> /tmp/ar-measure-verify
cd /tmp/ar-measure-verify
# export the three env vars above, then run the build sequence start to finish
# zero manual edits, zero "oh I also had to..." steps
```

If this fails, Gate 0.4 fails — fix the setup doc or the build config, not the
test.

## Emulator (instrumented tests — Gate 0.3+)

AVD `metiri` exists on this machine (API 36 google_apis x86_64, pixel_5):

- **WHPX acceleration is available.** The WMI `VirtualizationFirmwareEnabled`
  flag misleadingly reports False, but `HypervisorPresent` is True and
  `emulator -accel-check` reports "WHPX is installed and usable". The emulator
  boots in ~3 minutes.
- Boot headless (no window, software GPU):
  ```
  emulator -avd metiri -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect
  ```
- The Gate 0.3 ping test needs no ARCore (pure FFI call), so it runs on the
  plain google_apis image. Phase 4+ ARCore/Depth work WILL need a physical
  device — this emulator has no camera passthrough.
- `avdmanager` (the .bat wrapper) needs Windows-style env paths:
  `ANDROID_HOME='C:\Android\Sdk'` — msys-style `/c/Android/Sdk` makes it find
  no packages.

## Generated-code facts (verified from the actual uniffi 0.32.0 output)

- The JNA backend emits `ping()` as a **top-level function in package
  `uniffi.core`** (`import uniffi.core.ping`), not an `object Core` wrapper.
- The generated Kotlin requires the `net.java.dev.jna:jna` runtime dependency —
  on Android this MUST be the `@aar` artifact (ships `libjnidispatch.so` per
  ABI); the plain jar compiles but crashes at runtime (Gate 0.3 catch).
- Library loading is by component name: `findLibraryName("core")` → `libcore.so`
  in jniLibs — matches the cargo-ndk output layout.
- jniLibs, the generated `uniffi/` dir, and all build outputs are gitignored
  and rebuilt by the sequence above.

## Known risk areas to watch (from prior research, not yet hit in practice)

- **JNA/JNI callback GC pinning**: irrelevant until Phase 4's tracking-quality
  polling — but if anyone is tempted to switch from poll-based to callback-based
  updates later, re-read why that was rejected (Kotlin GC can collect a callback
  mid-flight unless explicitly pinned; poll-based sidesteps this entirely).
- **Enum round-tripping across FFI**: explicitly tested in Gate 1.6 — UniFFI
  generally handles this well but it's the one place past projects have reported
  subtle bugs (mismatched variant ordering between Rust and generated Kotlin if
  the enum is hand-edited on either side after generation).
- **AGP 9 built-in Kotlin**: if a future migration re-adds
  `org.jetbrains.kotlin.android`, AGP 9.x will fail the build with a pointed
  error. Kotlin compiler version is AGP-managed now.

## Re-verify cadence

Re-verify ARCore facts (device catalog, Depth API coverage, min SDK) against
`developers.google.com/ar/devices` and `developers.google.com/ar/develop` if
more than ~2 months have passed since this doc was written. ARCore device
support and API surface shift.