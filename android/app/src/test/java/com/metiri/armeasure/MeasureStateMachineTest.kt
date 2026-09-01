package com.metiri.armeasure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.core.Point3

/**
 * Gate 3.1: every legal transition succeeds, every illegal transition is a
 * no-op (same state, never a crash). Enumerates ALL (state, action) pairs from
 * the locked transition table, including the illegal ones explicitly.
 *
 * The distance function is injected as a stub — the machine never touches FFI,
 * so these run as plain JVM tests without the Rust .so.
 */
class MeasureStateMachineTest {

    private val p1 = Point3(0f, 0f, 0f)
    private val p2 = Point3(3f, 4f, 0f)

    /** Stub: constant distance, proves the payload carries the fn's output. */
    private val stubDistance: (Point3, Point3) -> Float = { _, _ -> 1.0f }

    /** Real math stub: 3-4-5 triangle gives exactly 5.0f. */
    private val mathDistance: (Point3, Point3) -> Float = { a, b ->
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    // --- Idle ---

    @Test
    fun idle_tapHit_isNoOp() {
        val result = MeasureMachine.transition(MeasureState.Idle, MeasureAction.TapHit(p1), stubDistance)
        assertSame(MeasureState.Idle, result)
    }

    @Test
    fun idle_tapMiss_isNoOp() {
        val result = MeasureMachine.transition(MeasureState.Idle, MeasureAction.TapMiss, stubDistance)
        assertSame(MeasureState.Idle, result)
    }

    @Test
    fun idle_beginMeasure_armsFirstPoint() {
        val result = MeasureMachine.transition(MeasureState.Idle, MeasureAction.BeginMeasure, stubDistance)
        assertSame(MeasureState.SelectingFirstPoint, result)
    }

    @Test
    fun idle_cancel_isNoOp() {
        val result = MeasureMachine.transition(MeasureState.Idle, MeasureAction.Cancel, stubDistance)
        assertSame(MeasureState.Idle, result)
    }

    @Test
    fun idle_reset_isNoOp() {
        val result = MeasureMachine.transition(MeasureState.Idle, MeasureAction.Reset, stubDistance)
        assertSame(MeasureState.Idle, result)
    }

    // --- SelectingFirstPoint ---

    @Test
    fun selectingFirstPoint_tapHit_becomesMeasuringWithTapPoint() {
        val result = MeasureMachine.transition(MeasureState.SelectingFirstPoint, MeasureAction.TapHit(p1), stubDistance)
        assertEquals(MeasureState.Measuring(p1), result)
    }

    @Test
    fun selectingFirstPoint_tapMiss_isNoOp() {
        val result = MeasureMachine.transition(MeasureState.SelectingFirstPoint, MeasureAction.TapMiss, stubDistance)
        assertSame(MeasureState.SelectingFirstPoint, result)
    }

    @Test
    fun selectingFirstPoint_beginMeasure_isNoOp() {
        val result = MeasureMachine.transition(MeasureState.SelectingFirstPoint, MeasureAction.BeginMeasure, stubDistance)
        assertSame(MeasureState.SelectingFirstPoint, result)
    }

    @Test
    fun selectingFirstPoint_cancel_returnsToIdle() {
        val result = MeasureMachine.transition(MeasureState.SelectingFirstPoint, MeasureAction.Cancel, stubDistance)
        assertSame(MeasureState.Idle, result)
    }

    @Test
    fun selectingFirstPoint_reset_returnsToIdle() {
        val result = MeasureMachine.transition(MeasureState.SelectingFirstPoint, MeasureAction.Reset, stubDistance)
        assertSame(MeasureState.Idle, result)
    }

    // --- Measuring ---

    @Test
    fun measuring_tapHit_completesWithDistanceFromFn() {
        val result = MeasureMachine.transition(MeasureState.Measuring(p1), MeasureAction.TapHit(p2), mathDistance)
        val complete = result as MeasureState.Complete
        assertEquals(p1, complete.start)
        assertEquals(p2, complete.end)
        // 3-4-5 triangle: distance must equal the injected fn's output exactly.
        assertEquals(5.0f, complete.distance, 1e-6f)
    }

    @Test
    fun measuring_tapMiss_isNoOp() {
        val state = MeasureState.Measuring(p1)
        val result = MeasureMachine.transition(state, MeasureAction.TapMiss, stubDistance)
        assertSame(state, result)
    }

    @Test
    fun measuring_beginMeasure_isNoOp() {
        val state = MeasureState.Measuring(p1)
        val result = MeasureMachine.transition(state, MeasureAction.BeginMeasure, stubDistance)
        assertSame(state, result)
    }

    @Test
    fun measuring_cancel_returnsToIdle() {
        val result = MeasureMachine.transition(MeasureState.Measuring(p1), MeasureAction.Cancel, stubDistance)
        assertSame(MeasureState.Idle, result)
    }

    @Test
    fun measuring_reset_returnsToIdle() {
        val result = MeasureMachine.transition(MeasureState.Measuring(p1), MeasureAction.Reset, stubDistance)
        assertSame(MeasureState.Idle, result)
    }

    // --- Complete ---

    private fun completed(): MeasureState.Complete =
        MeasureMachine.transition(MeasureState.Measuring(p1), MeasureAction.TapHit(p2), mathDistance)
            as MeasureState.Complete

    @Test
    fun complete_tapHit_isNoOpKeepingPayload() {
        val complete = completed()
        val result = MeasureMachine.transition(complete, MeasureAction.TapHit(p1), stubDistance)
        assertSame(complete, result)
    }

    @Test
    fun complete_tapMiss_isNoOp() {
        val complete = completed()
        val result = MeasureMachine.transition(complete, MeasureAction.TapMiss, stubDistance)
        assertSame(complete, result)
    }

    @Test
    fun complete_beginMeasure_isNoOp() {
        val complete = completed()
        val result = MeasureMachine.transition(complete, MeasureAction.BeginMeasure, stubDistance)
        assertSame(complete, result)
    }

    @Test
    fun complete_cancel_isNoOp() {
        val complete = completed()
        val result = MeasureMachine.transition(complete, MeasureAction.Cancel, stubDistance)
        assertSame(complete, result)
    }

    @Test
    fun complete_reset_returnsToIdle() {
        val result = MeasureMachine.transition(completed(), MeasureAction.Reset, stubDistance)
        assertSame(MeasureState.Idle, result)
    }

    // --- Reducer invariants ---

    @Test
    fun completeDistanceAlwaysEqualsInjectedFn() {
        // Start must differ from the injected fn's output so the assertion is
        // meaningful even for a constant stub.
        val complete = MeasureMachine.transition(MeasureState.Measuring(p1), MeasureAction.TapHit(p2), stubDistance)
        val c = complete as MeasureState.Complete
        assertEquals(1.0f, c.distance, 0f)
        assertEquals(1.0f, stubDistance(c.start, c.end), 0f)
    }

    @Test
    fun everyActionReturnsAStateAndNeverThrows() {
        val states = listOf(
            MeasureState.Idle,
            MeasureState.SelectingFirstPoint,
            MeasureState.Measuring(p1),
            completed(),
        )
        val actions = listOf<MeasureAction>(
            MeasureAction.TapHit(p1),
            MeasureAction.TapMiss,
            MeasureAction.BeginMeasure,
            MeasureAction.Cancel,
            MeasureAction.Reset,
        )
        for (s in states) {
            for (a in actions) {
                // Reducer must never throw and must always stay inside the
                // MeasureState universe (one of the four sealed variants).
                val result = MeasureMachine.transition(s, a, stubDistance)
                assertTrue(
                    "transition from $s on $a produced an unexpected state: $result",
                    result is MeasureState.Idle ||
                        result is MeasureState.SelectingFirstPoint ||
                        result is MeasureState.Measuring ||
                        result is MeasureState.Complete,
                )
            }
        }
    }
}