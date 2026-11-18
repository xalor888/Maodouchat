package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageSelectionToolbarPolicyTest {

    private fun msg(
        id: String,
        type: MessageType = MessageType.TEXT,
        status: MessageStatus = MessageStatus.SENT,
        content: String = "hi",
        starred: Boolean = false,
    ) = Message(id = id, chatId = "c1", senderId = "u1", content = content, type = type, status = status, starred = starred)

    private fun state(
        selected: List<Message>,
        chatIsGroup: Boolean = false,
        myMemberRole: String? = null,
        pinnedIds: Set<String> = emptySet(),
        isSecretChat: Boolean = false,
    ) = resolveMessageSelectionToolbar(
        selectedMessages = selected,
        chatIsGroup = chatIsGroup,
        myMemberRole = myMemberRole,
        pinnedMessageIds = pinnedIds,
        isSecretChat = isSecretChat,
        forwardBlockEnabled = true,
        copyBlockEnabled = true,
    )

    @Test
    fun `forwardable excludes system and secret chats`() {
        val selected = listOf(msg("m1"), msg("m2", MessageType.SYSTEM), msg("m3", MessageType.IMAGE))
        assertEquals(listOf("m1", "m3"), state(selected).forwardableMessages.map { it.id })
        assertTrue(state(selected, isSecretChat = true).forwardableMessages.isEmpty())
    }

    @Test
    fun `shouldStar true when any unstarred`() {
        assertFalse(state(listOf(msg("m1", starred = true))).shouldStar)
        assertTrue(state(listOf(msg("m1", starred = true), msg("m2"))).shouldStar)
    }

    @Test
    fun `shouldPin true when any unpinned`() {
        assertFalse(state(listOf(msg("m1")), pinnedIds = setOf("m1")).shouldPin)
        assertTrue(state(listOf(msg("m1"), msg("m2")), pinnedIds = setOf("m1")).shouldPin)
    }

    @Test
    fun `canBatchPin gated by group role`() {
        assertTrue(state(listOf(msg("m1")), chatIsGroup = true, myMemberRole = "ADMIN").canBatchPin)
        assertFalse(state(listOf(msg("m1")), chatIsGroup = true, myMemberRole = "MEMBER").canBatchPin)
        assertTrue(state(listOf(msg("m1")), chatIsGroup = false).canBatchPin)
        assertFalse(state(listOf(msg("m1", MessageType.SYSTEM)), chatIsGroup = false).canBatchPin)
    }

    @Test
    fun `selectableIds excludes system skdist sending and preparing`() {
        val all = listOf(
            msg("m1"),
            msg("m2", MessageType.SYSTEM),
            msg("m3", MessageType.SK_DIST),
            msg("m4", status = MessageStatus.SENDING),
            msg("m5"),
        )
        assertEquals(setOf("m1"), selectableMessageIds(all, setOf("m5")))
    }

    @Test
    fun `copyText joins copyable text and null when none`() {
        val selected = listOf(msg("m1", content = "hello"), msg("m2", MessageType.IMAGE, content = "pic"), msg("m3", content = "  "))
        assertEquals("hello", state(selected).copyText)
        assertEquals("a\nb", state(listOf(msg("m1", content = "a"), msg("m2", content = "b"))).copyText)
        assertNull(state(listOf(msg("m1", MessageType.IMAGE))).copyText)
        assertNull(state(listOf(msg("m1", content = "  "))).copyText)
    }
}
