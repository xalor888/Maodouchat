package com.maodouchat.domain.messaging

import com.maodouchat.core.model.User
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatNameResolvePolicyTest {

    private val peers = mapOf(
        "u2" to User(id = "u2", name = "Bob", nickname = "Bobby"),
        "u3" to User(id = "u3", name = "Carol", nickname = "  ")
    )

    private fun resolve(
        groupName: String? = null,
        participantIds: String = "u1, u2, u3",
        ownerUserId: String = "u1"
    ) = runBlocking {
        ChatNameResolvePolicy.resolve(
            groupName = groupName,
            participantIds = participantIds,
            ownerUserId = ownerUserId,
            peerName = { peers[it]?.let(ChatNameResolvePolicy::displayName) }
        )
    }

    @Test
    fun `group name wins over participants`() {
        assertEquals("Weekend", resolve(groupName = "Weekend"))
    }

    @Test
    fun `blank group name falls through to peer name`() {
        assertEquals("Bobby", resolve(groupName = "  "))
    }

    @Test
    fun `skips blank ids and owner, takes first other peer`() {
        assertEquals(
            "Bobby",
            resolve(participantIds = " , u1, u2, u3", ownerUserId = "u1")
        )
    }

    @Test
    fun `peer lookup returning null yields empty string`() {
        assertEquals("", resolve(participantIds = "u9"))
    }

    @Test
    fun `empty participant ids yield empty string`() {
        assertEquals("", resolve(participantIds = ""))
    }

    @Test
    fun `display name prefers non-blank nickname, falls back to name`() {
        assertEquals("Bobby", ChatNameResolvePolicy.displayName(peers.getValue("u2")))
        assertEquals("Carol", ChatNameResolvePolicy.displayName(peers.getValue("u3")))
        assertEquals("Dave", ChatNameResolvePolicy.displayName(User(id = "u4", name = "Dave")))
    }
}
