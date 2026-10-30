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

    @Test
    fun group_prefersMappedName_blankMappedFallsToTruncated() {
        val state = ChatDetailUiState(chatIsGroup = true)
        val named = senderDisplayName(
            state = state,
            message = message("abcdefghij"),
            isOwn = false,
            participantNamesById = mapOf("abcdefghij" to "群友名"),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("群友名", named)

        val blankMapped = senderDisplayName(
            state = state,
            message = message("abcdefghij"),
            isOwn = false,
            participantNamesById = mapOf("abcdefghij" to "  "),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("abcdefgh", blankMapped)
    }

    @Test
    fun directChat_contactBlankFallsToMapped_thenUnknown() {
        val state = ChatDetailUiState(
            contact = User(id = "", name = ""),
            chatIsGroup = false,
        )
        val mapped = senderDisplayName(
            state = state,
            message = message("peer"),
            isOwn = false,
            participantNamesById = mapOf("peer" to "列表名"),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("列表名", mapped)

        val unknown = senderDisplayName(
            state = state,
            message = message(""),
            isOwn = false,
            participantNamesById = emptyMap(),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("未知", unknown)
    }

    @Test
    fun listSenderLabel_nullChat_shortIdReturnedAsIs() {
        assertEquals("abc", listSenderLabel(null, "abc", unknownLabel = "未知"))
        assertEquals("abcd1234", listSenderLabel(null, "abcd1234zzzz"))
    }

    @Test
    fun listSenderLabel_unmatchedParticipant_fallsBackToTruncated() {
        val chat = Chat(
            id = "c",
            participants = listOf(User(id = "other", name = "别人")),
            isGroup = true,
        )
        assertEquals("abcdefgh", listSenderLabel(chat, "abcdefghij"))
    }

    @Test
    fun resolveChatDetailSenderName_matchesSenderDisplayName() {
        val state = ChatDetailUiState(
            contact = User(id = "peer", name = "", nickname = "备注名"),
            chatIsGroup = false,
        )
        val msg = message("peer")
        val names = mapOf("peer" to "列表名")
        assertEquals(
            senderDisplayName(state, msg, false, names, "未知", "群成员"),
            com.maodouchat.ui.screen.chatdetail.resolveChatDetailSenderName(
                msg, state, names, "未知", "群成员",
            ),
        )
    }

    @Test
    fun directChat_contactNameFallsToIdWhenBlankNicknameAndName() {
        val state = ChatDetailUiState(
            contact = User(id = "peer007", name = ""),
            chatIsGroup = false,
        )
        val name = senderDisplayName(
            state = state,
            message = message("peer007"),
            isOwn = false,
            participantNamesById = emptyMap(),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("peer007", name)
    }

    @Test
    fun directChat_nicknameWithWhitespace_isTrimmed() {
        val state = ChatDetailUiState(
            contact = User(id = "p", name = "原始名", nickname = "  备注名  "),
            chatIsGroup = false,
        )
        val name = senderDisplayName(
            state = state,
            message = message("p"),
            isOwn = false,
            participantNamesById = emptyMap(),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("备注名", name)
    }

    @Test
    fun group_ownMessage_returnsNull() {
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
    fun directChat_blankMappedName_skipsToTruncatedId() {
        val state = ChatDetailUiState(
            contact = User(id = "", name = ""),
            chatIsGroup = false,
        )
        val name = senderDisplayName(
            state = state,
            message = message("peer"),
            isOwn = false,
            participantNamesById = mapOf("peer" to "  "),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("peer", name)
    }

    @Test
    fun resolveChatDetailSenderName_derivesIsOwnFromSenderId() {
        val state = ChatDetailUiState(
            currentUserId = "me",
            contact = User(id = "peer", name = "", nickname = "备注名"),
            chatIsGroup = false,
        )
        assertNull(
            com.maodouchat.ui.screen.chatdetail.resolveChatDetailSenderName(
                message("me"), state, emptyMap(), "未知", "群成员",
            )
        )
        assertEquals(
            "备注名",
            com.maodouchat.ui.screen.chatdetail.resolveChatDetailSenderName(
                message("peer"), state, emptyMap(), "未知", "群成员",
            ),
        )
    }

    @Test
    fun group_mappedNameWithWhitespace_isTrimmed() {
        val state = ChatDetailUiState(chatIsGroup = true)
        val name = senderDisplayName(
            state = state,
            message = message("peer"),
            isOwn = false,
            participantNamesById = mapOf("peer" to "  群友  "),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("群友", name)
    }

    @Test
    fun directChat_whitespaceNicknameFallsToId() {
        val state = ChatDetailUiState(
            contact = User(id = "peer9", name = "", nickname = "  "),
            chatIsGroup = false,
        )
        val name = senderDisplayName(
            state = state,
            message = message("peer9"),
            isOwn = false,
            participantNamesById = emptyMap(),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("peer9", name)
    }

    @Test
    fun group_senderIdWithSurroundingWhitespace_truncatesAfterTrim() {
        val state = ChatDetailUiState(chatIsGroup = true)
        val name = senderDisplayName(
            state = state,
            message = message("  abcdefghij  "),
            isOwn = false,
            participantNamesById = emptyMap(),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("abcdefgh", name)
    }

    @Test
    fun directChat_contactDisplayNameBeatsMappedName() {
        val state = ChatDetailUiState(
            contact = User(id = "peer", name = "原始名", nickname = "备注名"),
            chatIsGroup = false,
        )
        val name = senderDisplayName(
            state = state,
            message = message("peer"),
            isOwn = false,
            participantNamesById = mapOf("peer" to "列表名"),
            unknownLabel = "未知",
            groupMemberLabel = "群成员",
        )
        assertEquals("备注名", name)
    }

    @Test
    fun group_blankMemberLabelFallsToUnknown() {
        val state = ChatDetailUiState(chatIsGroup = true)
        val name = senderDisplayName(
            state = state,
            message = message(""),
            isOwn = false,
            participantNamesById = emptyMap(),
            unknownLabel = "未知",
            groupMemberLabel = "",
        )
        assertEquals("未知", name)
    }

    private fun message(senderId: String) = Message(
        id = "m",
        chatId = "c",
        senderId = senderId,
        content = "hi",
        status = MessageStatus.SENT,
    )
}
