package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsModerationModelsTest {

    @Test
    fun `defaults describe an unloaded reports section`() {
        val state = ModerationUiState()
        assertEquals(true, state.isLoading)
        assertEquals(false, state.isUpdating)
        assertEquals("REPORTS", state.section)
        assertEquals("OPEN", state.statusFilter)
        assertEquals(emptyList<ReportResponse>(), state.reports)
        assertEquals(emptyList<RiskEventResponse>(), state.riskEvents)
        assertEquals(emptyList<ModerationRuleResponse>(), state.rules)
        assertEquals(null, state.errorMessage)
        assertEquals(null, state.infoMessage)
    }

    @Test
    fun `copy changes only the given fields`() {
        val loaded = ModerationUiState(
            isLoading = false, section = "RULES", errorMessage = "boom"
        )
        assertEquals(false, loaded.isLoading)
        assertEquals("RULES", loaded.section)
        assertEquals("boom", loaded.errorMessage)
        assertEquals("OPEN", loaded.statusFilter)
    }
}
