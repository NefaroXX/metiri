package com.metiri.armeasure

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 3 follow-up: the session lock / [ArSessionManager.withSession] gate.
 * Verifies (a) withSession surfaces exactly the current session and (b)
 * concurrent withSession callers are strictly serialized — the guarantee that
 * closes the GL-frame vs UI-thread create/close use-after-close race by
 * construction. Pure JVM: no Session object can exist here (ARCore needs a
 * device), so the contract is checked on the lock semantics, not the payload.
 */
class ArSessionManagerTest {

    @Test
    fun withSessionReturnsCurrentSession() {
        // JVM: no session is ever assigned (Session requires ARCore/device),
        // so the contract reduces to: withSession sees exactly what the
        // public getter sees (both null here).
        assertNull(ArSessionManager.withSession { it })
        assertEquals(ArSessionManager.session, ArSessionManager.withSession { it })
    }

    @Test
    fun withSessionSerializesConcurrentAccess() {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val threads = (0 until 8).map {
            Thread {
                repeat(50) {
                    ArSessionManager.withSession { _ ->
                        val current = active.incrementAndGet()
                        // maxActive only ever reaches 1 if the lock serializes.
                        maxActive.accumulateAndGet(current) { a, b -> maxOf(a, b) }
                        Thread.sleep(1)
                        active.decrementAndGet()
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals("withSession must serialize concurrent callers", 1, maxActive.get())
        assertEquals("all threads must have released the block", 0, active.get())
    }
}
