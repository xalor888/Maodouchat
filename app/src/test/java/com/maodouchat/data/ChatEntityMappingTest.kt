package com.maodouchat.data

import com.maodouchat.data.local.entity.ChatEntity
import com.maodouchat.data.local.entity.toDomain
import com.maodouchat.data.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEntityMappingTest {
    @Test
    fun `toDomain keeps participant ids when user profile rows are missing`() {
        val entity = ChatEntity(
            id = "chat-1",
            participantIds = "me,peer-missing"
        )
        val domain = entity.toDomain(participantsMap = mapOf("me" to User(id = "me", name = "Me")))
        assertEquals(listOf("me", "peer-missing"), domain.participants.map { it.id })
        val stub = domain.participants.first { it.id == "peer-missing" }
        assertEquals("", stub.name)
        // displayName falls back to id so list titles never render blank for stub profiles.
        assertEquals("peer-missing", stub.displayName)
        assertTrue(stub.id.isNotBlank())
    }

    @Test
    fun `toDomain prefers profile map over stubs`() {
        val entity = ChatEntity(id = "chat-2", participantIds = "peer-1")
        val domain = entity.toDomain(
            participantsMap = mapOf("peer-1" to User(id = "peer-1", name = "Alice", nickname = "A"))
        )
        assertEquals(1, domain.participants.size)
        assertEquals("Alice", domain.participants.single().name)
        assertEquals("A", domain.participants.single().displayName)
    }
}
