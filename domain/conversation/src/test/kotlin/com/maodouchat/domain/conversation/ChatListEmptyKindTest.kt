package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatListEmptyKindTest {

    @Test
    fun `search query wins`() {
        assertEquals(ChatListEmptyKind.SEARCH, chatListEmptyKind(true, false, null))
        assertEquals(ChatListEmptyKind.SEARCH, chatListEmptyKind(true, true, "system:groups"))
    }

    @Test
    fun `archived wins over folder`() {
        assertEquals(ChatListEmptyKind.ARCHIVED, chatListEmptyKind(false, true, "system:groups"))
    }

    @Test
    fun `system folders map one by one`() {
        assertEquals(ChatListEmptyKind.UNREAD, chatListEmptyKind(false, false, "system:unread"))
        assertEquals(ChatListEmptyKind.GROUPS, chatListEmptyKind(false, false, "system:groups"))
        assertEquals(ChatListEmptyKind.DIRECT, chatListEmptyKind(false, false, "system:direct"))
        assertEquals(ChatListEmptyKind.SECRET, chatListEmptyKind(false, false, "system:secret"))
        assertEquals(ChatListEmptyKind.LOCKED, chatListEmptyKind(false, false, "system:locked"))
    }

    @Test
    fun `custom folder id maps to custom folder`() {
        assertEquals(ChatListEmptyKind.CUSTOM_FOLDER, chatListEmptyKind(false, false, "custom:1"))
    }

    @Test
    fun `no selection maps to default`() {
        assertEquals(ChatListEmptyKind.DEFAULT, chatListEmptyKind(false, false, null))
        assertEquals(ChatListEmptyKind.DEFAULT, chatListEmptyKind(false, false, ""))
        assertEquals(ChatListEmptyKind.DEFAULT, chatListEmptyKind(false, false, "   "))
    }
}
