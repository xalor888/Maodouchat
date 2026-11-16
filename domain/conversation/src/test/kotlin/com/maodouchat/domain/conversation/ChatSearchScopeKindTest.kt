package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatSearchScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchScopeKindTest {

    @Test
    fun `all seven scopes map to their kinds`() {
        assertEquals(ChatSearchScopeKind.ALL, chatSearchScopeKind(ChatSearchScope.ALL))
        assertEquals(ChatSearchScopeKind.TEXT, chatSearchScopeKind(ChatSearchScope.TEXT))
        assertEquals(ChatSearchScopeKind.VOICE, chatSearchScopeKind(ChatSearchScope.VOICE))
        assertEquals(ChatSearchScopeKind.TRANSLATION, chatSearchScopeKind(ChatSearchScope.TRANSLATION))
        assertEquals(ChatSearchScopeKind.STARRED, chatSearchScopeKind(ChatSearchScope.STARRED))
        assertEquals(ChatSearchScopeKind.MEDIA, chatSearchScopeKind(ChatSearchScope.MEDIA))
        assertEquals(ChatSearchScopeKind.MENTIONS, chatSearchScopeKind(ChatSearchScope.MENTIONS))
    }
}
