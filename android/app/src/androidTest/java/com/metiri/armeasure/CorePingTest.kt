package com.metiri.armeasure

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.core.ping

@RunWith(AndroidJUnit4::class)
class CorePingTest {

    @Test
    fun pingReturnsPong() {
        assertEquals("pong", ping())
    }
}