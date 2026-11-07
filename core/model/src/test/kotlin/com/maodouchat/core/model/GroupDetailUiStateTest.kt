package com.maodouchat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupDetailUiStateTest {

    @Test
    fun `owner and admin can manage group, member cannot`() {
        assertTrue(GroupDetailUiState(myRole = "OWNER").canManageGroup)
        assertTrue(GroupDetailUiState(myRole = "ADMIN").canManageGroup)
        assertFalse(GroupDetailUiState(myRole = "MEMBER").canManageGroup)
    }

    @Test
    fun `isOwner only for OWNER`() {
        assertTrue(GroupDetailUiState(myRole = "OWNER").isOwner)
        assertFalse(GroupDetailUiState(myRole = "ADMIN").isOwner)
    }

    @Test
    fun `defaults are loading with empty members`() {
        val state = GroupDetailUiState()
        assertTrue(state.isLoading)
        assertTrue(state.members.isEmpty())
        assertFalse(state.canManageGroup)
    }
}
