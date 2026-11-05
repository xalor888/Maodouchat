package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RefreshOutcomeModelsTest {

    @Test
    fun `refresh outcomes cover success and three failures`() {
        assertEquals("tok", (RefreshOutcome.Success("tok") as RefreshOutcome.Success).token)
        assertNotEquals(RefreshOutcome.SessionDead, RefreshOutcome.TransientFailure)
        assertNotEquals(RefreshOutcome.TransientFailure, RefreshOutcome.SessionChanged)
        assertNotEquals(RefreshOutcome.SessionChanged, RefreshOutcome.SessionDead)
    }
}
