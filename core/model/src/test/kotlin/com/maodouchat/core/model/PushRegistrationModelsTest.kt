package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PushRegistrationModelsTest {

    @Test
    fun `registration lifecycle states exist in order`() {
        assertEquals(
            listOf(
                RegistrationState.UNKNOWN,
                RegistrationState.INITIALIZING,
                RegistrationState.REGISTERED,
                RegistrationState.FAILED,
            ),
            RegistrationState.entries.toList(),
        )
    }

    @Test
    fun `state names round-trip`() {
        assertEquals(
            RegistrationState.REGISTERED,
            RegistrationState.valueOf("REGISTERED"),
        )
    }
}
