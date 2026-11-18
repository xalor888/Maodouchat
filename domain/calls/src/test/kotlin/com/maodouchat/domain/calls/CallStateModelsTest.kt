package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertEquals

class CallStateModelsTest {

    @Test
    fun `call state has all five states`() {
        assertEquals(5, CallState.entries.size)
        assertEquals(CallState.CONNECTED, CallState.valueOf("CONNECTED"))
    }
}
