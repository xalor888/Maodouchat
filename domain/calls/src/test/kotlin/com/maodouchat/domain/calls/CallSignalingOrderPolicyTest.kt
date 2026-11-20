package com.maodouchat.domain.calls

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

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
