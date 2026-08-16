//! Geometry/measurement engine for AR Measure.
//!
//! Phase 0: FFI smoke gate only. Everything `#[uniffi::export]`ed here crosses
//! the UniFFI boundary into Kotlin on Android. Per the plan's cross-cutting
//! rules: no panics across FFI — all fallible paths return `Result`.

uniffi::setup_scaffolding!();

/// Trivial FFI smoke function — Gate 0.3 asserts `Core.ping() == "pong"` from Kotlin.
#[uniffi::export]
pub fn ping() -> String {
    "pong".into()
}
