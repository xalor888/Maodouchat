package com.maodouchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderKeyRetrySessionPolicyTest {
    @Test
    fun continuesWhenSessionStable() {
        assertTrue(
            SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u1")
        )
    }

    @Test
    fun abortsOnLogoutOrSwitch() {
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", null, "u1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "", "u1"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("u1", "tok", "u2"))
        assertFalse(SenderKeyRetrySessionPolicy.mayContinueBatch("", "tok", "u1"))
    }
}
