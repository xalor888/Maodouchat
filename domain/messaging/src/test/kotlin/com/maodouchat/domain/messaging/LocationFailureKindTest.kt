package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocationFailureKindTest {

    @Test
    fun `known failures map one to one`() {
        assertEquals(LocationFailureKind.PERMISSION_REQUIRED, locationFailureKind(LocationFailure.PERMISSION_REQUIRED))
        assertEquals(LocationFailureKind.SERVICES_DISABLED, locationFailureKind(LocationFailure.SERVICES_DISABLED))
        assertEquals(LocationFailureKind.UNAVAILABLE, locationFailureKind(LocationFailure.UNAVAILABLE))
    }

    @Test
    fun `null failure maps to unknown like the old null branch`() {
        assertEquals(LocationFailureKind.UNKNOWN, locationFailureKind(null))
    }
}
