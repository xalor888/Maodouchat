package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatSearchMode
import com.maodouchat.core.model.ChatSearchScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchPlaceholderKindTest {

    @Test
    fun `semantic mode wins over starred scope`() {
        assertEquals(
            ChatSearchPlaceholderKind.SEMANTIC,
            chatSearchPlaceholderKind(ChatSearchMode.SEMANTIC, ChatSearchScope.STARRED)
        )
    }

    @Test
    fun `starred scope in keyword mode uses starred placeholder`() {
        assertEquals(
            ChatSearchPlaceholderKind.STARRED,
            chatSearchPlaceholderKind(ChatSearchMode.KEYWORD, ChatSearchScope.STARRED)
        )
    }

    @Test
    fun `keyword mode with a plain scope uses content placeholder`() {
        assertEquals(
            ChatSearchPlaceholderKind.CONTENT,
            chatSearchPlaceholderKind(ChatSearchMode.KEYWORD, ChatSearchScope.ALL)
        )
        assertEquals(
            ChatSearchPlaceholderKind.CONTENT,
            chatSearchPlaceholderKind(ChatSearchMode.KEYWORD, ChatSearchScope.TEXT)
        )
    }
}
