package com.maodouchat.domain.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListEmptyStatePolicyTest {

    @Test
    fun `load error state needs error and no search archive or folder`() {
        assertTrue(ChatListEmptyStatePolicy.isLoadErrorState("网络错误", false, false, null))
        assertFalse(ChatListEmptyStatePolicy.isLoadErrorState(null, false, false, null))
        assertFalse(ChatListEmptyStatePolicy.isLoadErrorState("网络错误", true, false, null))
        assertFalse(ChatListEmptyStatePolicy.isLoadErrorState("网络错误", false, true, null))
        assertFalse(ChatListEmptyStatePolicy.isLoadErrorState("网络错误", false, false, "system:groups"))
        assertTrue(ChatListEmptyStatePolicy.isLoadErrorState("网络错误", false, false, ""))
        assertTrue(ChatListEmptyStatePolicy.isLoadErrorState("网络错误", false, false, "  "))
    }

    @Test
    fun `empty actions hidden with search archive or folder`() {
        assertTrue(ChatListEmptyStatePolicy.showEmptyActions(false, false, null))
        assertFalse(ChatListEmptyStatePolicy.showEmptyActions(true, false, null))
        assertFalse(ChatListEmptyStatePolicy.showEmptyActions(false, true, null))
        assertFalse(ChatListEmptyStatePolicy.showEmptyActions(false, false, "system:groups"))
    }
}
