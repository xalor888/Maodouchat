package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.Test

class LocationFailureTest {

    @Test
    fun `exception carries its failure`() {
        val error = LocationException(LocationFailure.SERVICES_DISABLED)
        assertEquals(LocationFailure.SERVICES_DISABLED, error.failure)
        assertNull(error.cause)
    }

    @Test
    fun `exception keeps the wrapped cause`() {
        val cause = IllegalStateException("provider")
        val error = LocationException(LocationFailure.UNAVAILABLE, cause)
        assertSame(cause, error.cause)
    }

    @Test
    fun `all three failures exist`() {
        assertEquals(
            listOf(
                LocationFailure.PERMISSION_REQUIRED,
                LocationFailure.SERVICES_DISABLED,
                LocationFailure.UNAVAILABLE
            ),
            LocationFailure.entries.toList()
        )
    }
}
