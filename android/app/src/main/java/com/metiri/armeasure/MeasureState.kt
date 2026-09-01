package com.metiri.armeasure

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
 */
sealed interface MeasureState {
    object Idle : MeasureState
    object SelectingFirstPoint : MeasureState
    data class Measuring(val start: Point3) : MeasureState
    data class Complete(val start: Point3, val end: Point3, val distance: Float) : MeasureState
}

sealed interface MeasureAction {
    data class TapHit(val point: Point3) : MeasureAction
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
            is MeasureAction.TapHit -> MeasureState.Measuring(action.point)
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