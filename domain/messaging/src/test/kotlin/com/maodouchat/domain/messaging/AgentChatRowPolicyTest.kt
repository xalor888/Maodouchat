package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentChatRowPolicyTest {

    @Test
    fun `chatListRow joins id title preview and flags with tabs`() {
        assertEquals(
            "c1\tFamily\tHello\tgroup,unread=2",
            AgentChatRowPolicy.chatListRow("c1", "Family", "Hello", "group,unread=2"),
        )
        assertEquals("c2\tBob\t\t", AgentChatRowPolicy.chatListRow("c2", "Bob", "", ""))
    }

    @Test
    fun `chatDetail renders nine lines in fixed order`() {
        assertEquals(
            "id=c1\ntitle=Family\ntype=GROUP\nmuted=true\npinned=true\narchived=false\nunread=3\nmarkedUnread=true\nlast=Hello",
            AgentChatRowPolicy.chatDetail(
                id = "c1",
                title = "Family",
                chatType = "GROUP",
                muted = true,
                pinned = true,
                archived = false,
                unread = 3,
                markedUnread = true,
                lastPreview = "Hello",
            ),
        )
    }

    @Test
    fun `chatDetail renders pinned false and zero unread`() {
        assertEquals(
            "id=c2\ntitle=Bob\ntype=PRIVATE\nmuted=false\npinned=false\narchived=false\nunread=0\nmarkedUnread=false\nlast=",
            AgentChatRowPolicy.chatDetail(
                id = "c2",
                title = "Bob",
                chatType = "PRIVATE",
                muted = false,
                pinned = false,
                archived = false,
                unread = 0,
                markedUnread = false,
                lastPreview = "",
            ),
        )
    }
}
