package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPlayUiStateModelsTest {

    @Test
    fun `chain ui state defaults to loading with empty entries`() {
        val state = GroupChainUiState()
        assertTrue(state.loading)
        assertNull(state.error)
        assertTrue(state.chains.isEmpty())
        assertNull(state.detail)
    }

    @Test
    fun `chain detail state carries entries and join flag`() {
        val detail = GroupChainDetailState(
            chainId = "c1", title = "t", topic = "tp", active = true,
            entryCount = 2, maxEntries = 5, myJoined = true,
            entries = listOf(ChainEntryItem("e1", "a", 1, "hi")),
        )
        assertEquals(2, detail.entryCount)
        assertTrue(detail.myJoined)
        assertEquals("e1", detail.entries.single().id)
    }

    @Test
    fun `checkin pk and poll states default to not loading content`() {
        assertEquals(0, GroupCheckinUiState().streak)
        assertEquals(0, GroupPkUiState().pks.size)
        assertEquals(2, GroupPollUiState().options.size)
    }
}
