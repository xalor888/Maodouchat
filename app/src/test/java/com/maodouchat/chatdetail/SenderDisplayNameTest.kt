package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.User
import com.maodouchat.ui.screen.chatdetail.ChatDetailUiState
import com.maodouchat.ui.screen.chatdetail.senderDisplayName
import com.maodouchat.ui.screen.chatlist.listSenderLabel
import com.maodouchat.data.model.Chat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SenderDisplayNameTest {
    @Test
    fun ownMessageReturnsNull() {
        val state = ChatDetailUiState(currentUserId = "me", chatIsGroup = true)
        val name = senderDisplayName(
            state = state,
            message = message("me"),
            isOwn = true,
            participantNamesById = mapOf("me" to "自己"),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertNull(name)
    }

    @Test
    fun directChatPrefersContactDisplayNameOverBlankName() {
        val state = ChatDetailUiState(
            contact = User(id = "peer", name = "", nickname = "备注名"),
            chatIsGroup = false,
        )
        val name = senderDisplayName(
            state = state,
            message = message("peer"),
            isOwn = false,
            participantNamesById = emptyMap(),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("备注名", name)
    }

    @Test
    fun groupUnknownFallsBackToTruncatedIdThenMemberLabel() {
        val state = ChatDetailUiState(chatIsGroup = true)
        val truncated = senderDisplayName(
            state = state,
            message = message("abcdefghij"),
            isOwn = false,
            participantNamesById = emptyMap(),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("abcdefgh", truncated)

        val member = senderDisplayName(
            state = state,
            message = message(""),
            isOwn = false,
            participantNamesById = emptyMap(),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("群成员", member)
    }

    @Test
    fun listNotifyLabelUsesDisplayNameNotRawName() {
        val chat = Chat(
            id = "c",
            participants = listOf(User(id = "u2", name = "", nickname = "小豆")),
            isGroup = true,
        )
        assertEquals("小豆", listSenderLabel(chat, "u2"))
        assertEquals("abcd1234", listSenderLabel(null, "abcd1234zzzz", unknownLabel = "未知"))
    }

    private fun message(senderId: String) = Message(
        id = "m",
        chatId = "c",
        senderId = senderId,
        content = "hi",
        status = MessageStatus.SENT,
    )
}
