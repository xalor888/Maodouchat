package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSignalingOrderPolicyTest {

    @Test
    fun legacyZeroCursorIsAlwaysAccepted() {
        assertEquals(
            Admit.Accept,
            CallSignalingOrderPolicy.admit(Cursor(0, 0), Cursor(5, 9)),
        )
    }

    @Test
    fun staleSequenceIsRejected() {
        assertEquals(
            Admit.RejectStale,
            CallSignalingOrderPolicy.admit(Cursor(1, 2), Cursor(1, 3)),
        )
    }

    @Test
    fun epochBeatsSequenceInCompare() {
        assertTrue(CallSignalingOrderPolicy.compare(Cursor(2, 0), Cursor(1, 99)) > 0)
        assertFalse(CallSignalingOrderPolicy.compare(Cursor(1, 1), Cursor(1, 1)) != 0)
    }
}
