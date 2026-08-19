package com.maodouchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalInitializationPolicyTest {

    @Test
    fun completedSameAccountInitializationIsReused() {
        assertTrue(SignalInitializationPolicy.canReuse("u1", true, "u1"))
    }

    @Test
    fun failedDifferentOrAnonymousInitializationMustRun() {
        assertFalse(SignalInitializationPolicy.canReuse("u1", false, "u1"))
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, "u2"))
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, null))
        assertFalse(SignalInitializationPolicy.canReuse("u1", true, ""))
    }
}
