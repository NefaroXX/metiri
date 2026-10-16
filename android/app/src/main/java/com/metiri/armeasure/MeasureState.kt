package com.metiri.armeasure

import uniffi.core.MeasurementSource
import uniffi.core.Point3

/**
 * Phase 3 measurement-flow state machine (gate 3.1 — design locked).
 *
 * Pure Kotlin by design: no Android/ARCore types and no FFI calls inside the
 * machine itself, so JVM unit tests run without the Rust `.so`. Distance is
 * injected through [MeasureMachine.transition]'s `distanceFn`; production passes
 * `{ a, b -> uniffi.core.distance(a, b) }`, tests pass a stub. `Complete`'s
 * distance is computed inside the machine so the payload is always consistent.
 *
 * Transition table (implemented EXACTLY — see MeasureStateMachineTest):
 *
 * | from \ action       | TapHit(p)                   | TapMiss     | BeginMeasure          | Cancel | Reset |
 * |---------------------|-----------------------------|-------------|-----------------------|--------|-------|
 * | Idle                | Idle (no-op)                | Idle        | SelectingFirstPoint   | Idle   | Idle  |
 * | SelectingFirstPoint | Measuring(p)                | SFP (no-op) | SFP (no-op)           | Idle   | Idle  |
 * | Measuring(s)        | Complete(s,p,distanceFn)    | Meas(s)     | Meas(s) (no-op)       | Idle   | Idle  |
 * | Complete            | Complete (no-op)            | Complete    | Complete (no-op)      | Complete (no-op) | Idle |
 *
 * Every transition not listed above is a no-op returning the same state; the
 * reducer never throws. `TapMiss` is a no-op everywhere. `Cancel` aborts an
 * in-progress measurement (SelectingFirstPoint/Measuring); `Reset` clears a
 * completed one. Both return to [MeasureState.Idle].
 *
 * Gate 4.2 addition (orthogonal to the table above): `TapHit` carries a
 * [MeasurementSource] (default `FEATURE_POINT`; `SENSOR` = blank-wall
 * ray-plane estimate). It is provenance only — it never changes which
 * transition fires — and it is folded into `Complete.source` by the
 * "any estimate ⇒ SENSOR" rule.
 */
sealed interface MeasureState {
    object Idle : MeasureState
    object SelectingFirstPoint : MeasureState
    data class Measuring(
        val start: Point3,
        /** Source of the start point — [MeasurementSource.SENSOR] marks a blank-wall estimate. */
        val startSource: MeasurementSource = MeasurementSource.FEATURE_POINT,
    ) : MeasureState
    data class Complete(
        val start: Point3,
        val end: Point3,
        val distance: Float,
        /**
         * Combined source: [MeasurementSource.SENSOR] if EITHER endpoint is a
         * sensor estimate, else [MeasurementSource.FEATURE_POINT]. The default
         * keeps every pre-gate-4.2 construction/test unchanged (feature-point
         * is the real-hit source — planes are disabled).
         */
        val source: MeasurementSource = MeasurementSource.FEATURE_POINT,
    ) : MeasureState
}

sealed interface MeasureAction {
    /**
     * A tap that produced a point. [source] defaults to
     * [MeasurementSource.FEATURE_POINT] (ARCore feature-point hit); the
     * gate 4.2 blank-wall fallback passes [MeasurementSource.SENSOR] for a
     * ray-plane estimate (no anchor exists for those).
     */
    data class TapHit(
        val point: Point3,
        val source: MeasurementSource = MeasurementSource.FEATURE_POINT,
    ) : MeasureAction
    object TapMiss : MeasureAction
    object BeginMeasure : MeasureAction
    object Cancel : MeasureAction
    object Reset : MeasureAction
}

object MeasureMachine {

    /**
     * Pure reducer for the measurement state machine. Illegal transitions
     * return [state] unchanged; this function never throws.
     *
     * [distanceFn] is only invoked when producing a [MeasureState.Complete] —
     * the `Measuring` -> `Complete` transition — so `Complete.distance` always
     * equals `distanceFn(start, end)`.
     */
    fun transition(
        state: MeasureState,
        action: MeasureAction,
        distanceFn: (Point3, Point3) -> Float,
    ): MeasureState = when (state) {
        MeasureState.Idle -> when (action) {
            is MeasureAction.TapHit -> state
            MeasureAction.TapMiss -> state
            MeasureAction.BeginMeasure -> MeasureState.SelectingFirstPoint
            MeasureAction.Cancel -> state
            MeasureAction.Reset -> state
        }

        MeasureState.SelectingFirstPoint -> when (action) {
            is MeasureAction.TapHit -> MeasureState.Measuring(action.point, action.source)
            MeasureAction.TapMiss -> state
            MeasureAction.BeginMeasure -> state
            MeasureAction.Cancel -> MeasureState.Idle
            MeasureAction.Reset -> MeasureState.Idle
        }

        is MeasureState.Measuring -> when (action) {
            is MeasureAction.TapHit -> MeasureState.Complete(
                start = state.start,
                end = action.point,
                distance = distanceFn(state.start, action.point),
                // Honest combine: an estimate anywhere poisons the whole
                // measurement's provenance (never cleaner than its worst point).
                source = if (
                    state.startSource == MeasurementSource.SENSOR ||
                    action.source == MeasurementSource.SENSOR
                ) {
                    MeasurementSource.SENSOR
                } else {
                    MeasurementSource.FEATURE_POINT
                },
            )
            MeasureAction.TapMiss -> state
            MeasureAction.BeginMeasure -> state
            MeasureAction.Cancel -> MeasureState.Idle
            MeasureAction.Reset -> MeasureState.Idle
        }

        is MeasureState.Complete -> when (action) {
            is MeasureAction.TapHit -> state
            MeasureAction.TapMiss -> state
            MeasureAction.BeginMeasure -> state
            MeasureAction.Cancel -> state
            MeasureAction.Reset -> MeasureState.Idle
        }
    }
}