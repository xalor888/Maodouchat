package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertTrue

class NoRecipientDevicesExceptionTest {

    @Test
    fun noRecipientDevicesExceptionIsIllegalState() {
        val error = NoRecipientDevicesException()
        assertTrue(error is IllegalStateException)
    }
}
