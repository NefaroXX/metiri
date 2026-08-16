//! Geometry/measurement engine for AR Measure.
//!
//! Phase 1: `Point3`, `distance`, `Confidence` / `MeasurementSource` enums, and
//! the `Measurement` record — everything `#[uniffi::export]`ed crosses the
//! UniFFI boundary into Kotlin on Android. Per the plan's cross-cutting rules:
//! no panics across FFI — all fallible paths return `Result`; the core stays
//! stateless (UI-flow state lives in the Kotlin shell, per plan amendment).

uniffi::setup_scaffolding!();

/// Trivial FFI smoke function — Gate 0.3 asserts `ping() == "pong"` from Kotlin.
#[uniffi::export]
pub fn ping() -> String {
    "pong".into()
}

/// A point in AR space, meters, right-handed camera coordinates.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Point3 {
    pub x: f32,
    pub y: f32,
    pub z: f32,
}

/// Measurement confidence tier (see `score_confidence`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum Confidence {
    High,
    Medium,
    Low,
}

/// What the geometry for a measurement was derived from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MeasurementSource {
    Depth,
    Plane,
    FeaturePoint,
}

/// A completed measurement between two points.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct Measurement {
    pub start: Point3,
    pub end: Point3,
    pub distance: f32,
    pub confidence: Confidence,
    pub source: MeasurementSource,
}

/// Straight Euclidean distance between two points.
#[uniffi::export]
pub fn distance(a: Point3, b: Point3) -> f32 {
    let dx = a.x - b.x;
    let dy = a.y - b.y;
    let dz = a.z - b.z;
    (dx * dx + dy * dy + dz * dz).sqrt()
}

/// Confidence-scoring brackets, meters from the camera. Inclusive bounds.
pub const DISTANCE_BRACKET_MIN_M: f32 = 0.5;
pub const DISTANCE_BRACKET_MAX_M: f32 = 5.0;

/// Score measurement confidence from source, camera-to-point distance and
/// tracking quality.
///
/// The rule (Phase 1 gate 1.2 — the audited table; keep in sync with the
/// plan's bracket rule and `tests::confidence_table`):
///
/// | source        | in bracket [0.5, 5.0] m | tracking ok | confidence |
/// |---------------|-------------------------|-------------|------------|
/// | Depth         | yes                     | yes         | High       |
/// | Depth         | yes                     | no          | Medium     |
/// | Depth         | no                      | yes         | Medium     |
/// | Depth         | no                      | no          | Low        |
/// | Plane         | yes                     | yes         | Medium     |
/// | Plane         | yes                     | no          | Low        |
/// | Plane         | no                      | yes         | Low        |
/// | Plane         | no                      | no          | Low        |
/// | FeaturePoint  | any                     | any         | Medium     |
///
/// Notes on the two places the plan leaves room:
/// - "FeaturePoint caps at Medium **regardless of distance**" is read as
///   distance (and tracking) having no effect: always Medium.
/// - "Downgrade one tier, never below Low" is realized by the table above
///   (out-of-bracket Depth drops High→Medium and Medium→Low; Plane already
///   bottoms out at Low).
/// A NaN distance compares false against both bounds → treated as
/// out-of-bracket.
#[uniffi::export]
pub fn score_confidence(
    source: MeasurementSource,
    camera_distance_m: f32,
    tracking_ok: bool,
) -> Confidence {
    let in_bracket =
        (DISTANCE_BRACKET_MIN_M..=DISTANCE_BRACKET_MAX_M).contains(&camera_distance_m);
    match (source, in_bracket, tracking_ok) {
        (MeasurementSource::Depth, true, true) => Confidence::High,
        (MeasurementSource::Depth, true, false) => Confidence::Medium,
        (MeasurementSource::Depth, false, true) => Confidence::Medium,
        (MeasurementSource::Depth, false, false) => Confidence::Low,
        (MeasurementSource::Plane, true, true) => Confidence::Medium,
        (MeasurementSource::Plane, true, false) => Confidence::Low,
        (MeasurementSource::Plane, false, true) => Confidence::Low,
        (MeasurementSource::Plane, false, false) => Confidence::Low,
        (MeasurementSource::FeaturePoint, _, _) => Confidence::Medium,
    }
}

// TODO(phase-4): serialization round-trip test for `Measurement` once
// persistence is implemented — deliberately deferred per Phase 1 gate 1.5
// (scope guard: no persistence in this phase).

#[cfg(test)]
mod tests {
    use super::*;

    const EPS: f32 = 1e-4;

    fn assert_close(actual: f32, expected: f32) {
        assert!(
            (actual - expected).abs() <= EPS,
            "expected {expected}, got {actual}"
        );
    }

    /// Deterministic LCG so the property tests are reproducible — no rand dep.
    struct Lcg(u32);

    impl Lcg {
        fn next(&mut self) -> f32 {
            self.0 = self.0.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            (self.0 >> 8) as f32 / 16_777_216.0 // [0, 1)
        }

        fn point(&mut self) -> Point3 {
            let sample = |lcg: &mut Lcg| lcg.next() * 2_000_000.0 - 1_000_000.0;
            Point3 {
                x: sample(self),
                y: sample(self),
                z: sample(self),
            }
        }
    }

    // Gate 1.1 — distance unit tests

    #[test]
    fn distance_known_345_triangle() {
        let a = Point3 { x: 0.0, y: 0.0, z: 0.0 };
        let b = Point3 { x: 3.0, y: 4.0, z: 0.0 };
        assert_close(distance(a, b), 5.0);
    }

    #[test]
    fn distance_zero_same_point() {
        let p = Point3 { x: 1.5, y: -2.5, z: 3.5 };
        assert_eq!(distance(p, p), 0.0);
    }

    #[test]
    fn distance_negative_coordinates() {
        let a = Point3 { x: -1.0, y: -1.0, z: -1.0 };
        let b = Point3 { x: 2.0, y: 3.0, z: -1.0 }; // dx -3, dy -4, dz 0
        assert_close(distance(a, b), 5.0);
    }

    #[test]
    fn distance_large_coordinates_no_overflow_or_nan() {
        // ~1000 km offsets, still a clean 3-4-5 (dx/dy exact in f32 at this scale).
        let a = Point3 { x: 1_000_000.0, y: 0.0, z: 0.0 };
        let b = Point3 { x: 1_000_003.0, y: 4.0, z: 0.0 };
        assert_close(distance(a, b), 5.0);
        // Beyond exactness (f32 ulp at 1e10 is 1024) — only finiteness matters.
        let c = Point3 { x: 1e10, y: 0.0, z: 0.0 };
        let d = Point3 { x: 1e10, y: 1e10, z: 1e10 };
        let big = distance(c, d);
        assert!(big.is_finite() && big >= 0.0, "distance must stay finite: {big}");
    }

    // Gate 1.2 — confidence scoring table (the audited one)

    fn expect_score(source: MeasurementSource, d: f32, tracking_ok: bool, expected: Confidence) {
        assert_eq!(
            score_confidence(source, d, tracking_ok),
            expected,
            "source={source:?} d={d} tracking_ok={tracking_ok}"
        );
    }

    #[test]
    fn confidence_table_all_combinations() {
        // Boundary sweep: out-low, just-out-low, inclusive min, mid, inclusive
        // max, just-out-high, out-high. In-bracket = [0.5, 5.0] inclusive.
        for d in [0.1, 0.4999, 0.5, 2.0, 5.0, 5.0001, 7.0] {
            for tracking_ok in [true, false] {
                let in_bracket = (0.5..=5.0).contains(&d);
                for source in [
                    MeasurementSource::Depth,
                    MeasurementSource::Plane,
                    MeasurementSource::FeaturePoint,
                ] {
                    let expected = match (source, in_bracket, tracking_ok) {
                        (MeasurementSource::Depth, true, true) => Confidence::High,
                        (MeasurementSource::Depth, true, false) => Confidence::Medium,
                        (MeasurementSource::Depth, false, true) => Confidence::Medium,
                        (MeasurementSource::Depth, false, false) => Confidence::Low,
                        (MeasurementSource::Plane, true, true) => Confidence::Medium,
                        (MeasurementSource::Plane, true, false) => Confidence::Low,
                        (MeasurementSource::Plane, false, true) => Confidence::Low,
                        (MeasurementSource::Plane, false, false) => Confidence::Low,
                        (MeasurementSource::FeaturePoint, _, _) => Confidence::Medium,
                    };
                    expect_score(source, d, tracking_ok, expected);
                }
            }
        }
    }

    #[test]
    fn confidence_nan_distance_treated_as_out_of_bracket() {
        // NaN compares false against both bounds → out-of-bracket → downgrade.
        expect_score(MeasurementSource::Depth, f32::NAN, true, Confidence::Medium);
        expect_score(MeasurementSource::Plane, f32::NAN, true, Confidence::Low);
    }

    // Gate 1.3 + 1.4 — property tests, 1000 iterations each

    #[test]
    fn distance_symmetry_1000_random_pairs() {
        let mut lcg = Lcg(0x5EED_2026);
        for _ in 0..1000 {
            let a = lcg.point();
            let b = lcg.point();
            // Bit-exact: (a-b) is the exact negation of (b-a), squares match.
            assert_eq!(
                distance(a, b),
                distance(b, a),
                "symmetry violated for {a:?}, {b:?}"
            );
        }
    }

    #[test]
    fn distance_self_zero_1000_random_points() {
        let mut lcg = Lcg(0xCAFE_2026);
        for _ in 0..1000 {
            let p = lcg.point();
            assert_eq!(distance(p, p), 0.0, "self-distance nonzero for {p:?}");
        }
    }
}
