package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatDetailListDerivationsTest {

    private fun message(id: String) = Message(id = id, chatId = "c1", senderId = "u1", content = id)

    @Test
    fun `messagesById indexes by id`() {
        val byId = resolveMessagesById(listOf(message("m1"), message("m2")))
        assertEquals(listOf("m1", "m2"), byId.keys.sorted())
        assertEquals("m1", byId["m1"]?.content)
    }

    @Test
    fun `participant names prefer nickname then name then id`() {
        val names = resolveParticipantNamesById(
            participants = listOf(
                User(id = "u1", name = "Alice", nickname = "Al"),
                User(id = "u2", name = "Bob"),
                User(id = "u3", name = ""),
            ),
            memberNicknameByUser = emptyMap(),
            chatIsGroup = false,
        )
        assertEquals(mapOf("u1" to "Al", "u2" to "Bob", "u3" to "u3"), names)
    }

    @Test
    fun `group nicknames override participant names`() {
        val names = resolveParticipantNamesById(
            participants = listOf(User(id = "u1", name = "Alice")),
            memberNicknameByUser = mapOf("u1" to "群昵称", "u9" to "新人"),
            chatIsGroup = true,
        )
        assertEquals(mapOf("u1" to "群昵称", "u9" to "新人"), names)
    }

    @Test
    fun `non-group ignores member nicknames`() {
        val names = resolveParticipantNamesById(
            participants = listOf(User(id = "u1", name = "Alice")),
            memberNicknameByUser = mapOf("u1" to "群昵称"),
            chatIsGroup = false,
        )
        assertEquals(mapOf("u1" to "Alice"), names)
    }

    @Test
    fun `null participants yields only group nicknames`() {
        val names = resolveParticipantNamesById(
            participants = null,
            memberNicknameByUser = mapOf("u1" to "群昵称"),
            chatIsGroup = true,
        )
        assertEquals(mapOf("u1" to "群昵称"), names)
    }

    @Test
    fun `selected messages filters by ids in order`() {
        val selected = resolveSelectedMessages(
            listOf(message("m1"), message("m2"), message("m3")),
            setOf("m3", "m1"),
        )
        assertEquals(listOf("m1", "m3"), selected.map { it.id })
    }

    @Test
    fun `selection mode reflects non-empty ids`() {
        assertFalse(isMessageSelectionMode(emptySet()))
        assertTrue(isMessageSelectionMode(setOf("m1")))
    }
}
