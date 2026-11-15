package com.maodouchat.domain.messaging

import com.maodouchat.core.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatParticipantsPolicyTest {

    private fun user(id: String) = User(id = id, name = id)

    @Test
    fun `excludes current user and keeps order and others`() {
        val participants = listOf(user("u1"), user("u2"), user("u3"))
        val result = ChatParticipantsPolicy.excludingCurrentUser(participants, "u2")
        assertEquals(listOf(user("u1"), user("u3")), result)
    }

    @Test
    fun `excludes first participant`() {
        val participants = listOf(user("u1"), user("u2"))
        val result = ChatParticipantsPolicy.excludingCurrentUser(participants, "u1")
        assertEquals(listOf(user("u2")), result)
    }

    @Test
    fun `excludes last participant`() {
        val participants = listOf(user("u1"), user("u2"))
        val result = ChatParticipantsPolicy.excludingCurrentUser(participants, "u2")
        assertEquals(listOf(user("u1")), result)
    }

    @Test
    fun `absent current user returns list unchanged`() {
        val participants = listOf(user("u1"), user("u2"))
        val result = ChatParticipantsPolicy.excludingCurrentUser(participants, "u9")
        assertEquals(participants, result)
    }

    @Test
    fun `empty list returns empty list`() {
        assertEquals(
            emptyList<User>(),
            ChatParticipantsPolicy.excludingCurrentUser(emptyList<User>(), "u1")
        )
    }
}
