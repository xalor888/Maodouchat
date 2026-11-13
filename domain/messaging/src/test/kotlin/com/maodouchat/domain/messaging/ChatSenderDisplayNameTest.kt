package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatSenderDisplayNameTest {

    private fun state(group: Boolean, contactName: String) = ChatDetailUiState(
        chatIsGroup = group,
        contact = User(id = "peer", name = contactName),
    )

    /** `User.displayName` 会回落到 id，所以「真空白」必须 id 与 name 都空。 */
    private fun stateWithBlankContact(group: Boolean) = ChatDetailUiState(
        chatIsGroup = group,
        contact = User(id = "", name = ""),
    )

    private fun msg(senderId: String) = Message(id = "m", chatId = "c", senderId = senderId, content = "x")

    @Test
    fun `own message has no sender label`() {
        assertNull(
            senderDisplayName(
                state = state(group = false, contactName = "Bob"),
                message = msg("me"),
                isOwn = true,
                participantNamesById = mapOf("me" to "Me"),
            )
        )
    }

    @Test
    fun `direct chat prefers the contact display name`() {
        assertEquals(
            "Bob",
            senderDisplayName(
                state = state(group = false, contactName = "Bob"),
                message = msg("peer"),
                isOwn = false,
                participantNamesById = mapOf("peer" to "Mapped Bob"),
                unknownLabel = "未知",
            )
        )
    }

    @Test
    fun `direct chat falls back to mapped then truncated id then unknown`() {
        // contact.displayName 空白 -> 用 participantNamesById 的映射
        assertEquals(
            "Mapped Bob",
            senderDisplayName(
                state = stateWithBlankContact(group = false),
                message = msg("peer"),
                isOwn = false,
                participantNamesById = mapOf("peer" to "Mapped Bob"),
                unknownLabel = "未知",
            )
        )
        // 映射也缺失 -> 用截断后的 senderId
        assertEquals(
            "abcdefgh",
            senderDisplayName(
                state = stateWithBlankContact(group = false),
                message = msg("abcdefghijklmnop"),
                isOwn = false,
                participantNamesById = emptyMap(),
                unknownLabel = "未知",
            )
        )
        // senderId 也空 -> 最后才用 unknownLabel
        assertEquals(
            "未知",
            senderDisplayName(
                state = stateWithBlankContact(group = false),
                message = msg("  "),
                isOwn = false,
                participantNamesById = emptyMap(),
                unknownLabel = "未知",
            )
        )
    }

    @Test
    fun `group chat skips the contact name and uses the member label last`() {
        // 群聊优先用映射，不用 contact.displayName
        assertEquals(
            "Mapped Carol",
            senderDisplayName(
                state = state(group = true, contactName = "Bob"),
                message = msg("carol"),
                isOwn = false,
                participantNamesById = mapOf("carol" to "Mapped Carol"),
                groupMemberLabel = "群成员",
                unknownLabel = "未知",
            )
        )
        // 无映射 -> 截断 senderId
        assertEquals(
            "carol",
            senderDisplayName(
                state = state(group = true, contactName = "Bob"),
                message = msg("carol"),
                isOwn = false,
                participantNamesById = emptyMap(),
                groupMemberLabel = "群成员",
                unknownLabel = "未知",
            )
        )
        // 映射与 senderId 都不可用 -> groupMemberLabel
        assertEquals(
            "群成员",
            senderDisplayName(
                state = state(group = true, contactName = "Bob"),
                message = msg("  "),
                isOwn = false,
                participantNamesById = emptyMap(),
                groupMemberLabel = "群成员",
                unknownLabel = "未知",
            )
        )
        // 连 groupMemberLabel 都没给 -> unknownLabel
        assertEquals(
            "未知",
            senderDisplayName(
                state = state(group = true, contactName = "Bob"),
                message = msg("  "),
                isOwn = false,
                participantNamesById = emptyMap(),
                groupMemberLabel = "",
                unknownLabel = "未知",
            )
        )
    }

    @Test
    fun `contact display name never blanks because it falls back to the id`() {
        // User.displayName = nickname ?: name ?: id——所以 senderDisplayName 在单聊里
        // 几乎总能拿到一个非空值；unknownLabel 只在 contact 的 id 也为空时才用得上。
        assertEquals(
            "peer",
            senderDisplayName(
                state = state(group = false, contactName = "   "),
                message = msg("someoneelse"),
                isOwn = false,
                participantNamesById = emptyMap(),
                unknownLabel = "未知",
            )
        )
    }

    @Test
    fun `blank mapped name does not count as a hit`() {
        // 映射存在但全空白——不能命中，要继续往后回退
        assertEquals(
            "abcdefgh",
            senderDisplayName(
                state = stateWithBlankContact(group = false),
                message = msg("abcdefghijklmnop"),
                isOwn = false,
                participantNamesById = mapOf("abcdefghijklmnop" to "   "),
                unknownLabel = "未知",
            )
        )
    }
}
