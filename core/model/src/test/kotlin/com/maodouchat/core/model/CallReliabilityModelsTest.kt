package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CallReliabilityModelsTest {

    @Test
    fun `media permissions keep declaration order`() {
        assertEquals(
            listOf(CallMediaPermission.MICROPHONE, CallMediaPermission.CAMERA),
            CallMediaPermission.entries.toList()
        )
    }

}
