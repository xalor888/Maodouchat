package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatSearchMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchModeKindTest {

    @Test
    fun `both modes map to their kinds`() {
        assertEquals(ChatSearchModeKind.KEYWORD, chatSearchModeKind(ChatSearchMode.KEYWORD))
        assertEquals(ChatSearchModeKind.SEMANTIC, chatSearchModeKind(ChatSearchMode.SEMANTIC))
    }
}
