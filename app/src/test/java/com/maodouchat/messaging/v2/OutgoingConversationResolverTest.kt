package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OutgoingConversationResolverTest {
    @Test
    fun `cached direct conversation resolves without network`() = runTest {
        val harness = Harness(cached = directChat("cached", "alice"))

        val result = harness.resolver.resolve(harness.request(activeConversationId = "cached"))

        val resolved = result.getOrThrow()
        assertEquals("cached", resolved.conversation.id)
        assertEquals("alice", resolved.peerUserId)
        assertEquals(0, harness.fetchCount)
        assertEquals(0, harness.createCount)
        assertEquals(1, harness.cryptoCount)
    }

    @Test
    fun `incomplete known conversation is fetched and cached before use`() = runTest {
        val remote = groupChat("group", revision = 12)
        val harness = Harness(fetched = listOf(remote))

        val result = harness.resolver.resolve(
            harness.request(
                activeConversationId = "group",
                paintedConversation = Chat(id = "group"),
            ),
        )

        val resolved = result.getOrThrow()
        assertEquals(12, resolved.conversation.memberRevision)
        assertEquals(listOf("group"), harness.cachedWrites.map(Chat::id))
        assertEquals(1, harness.fetchCount)
        assertEquals(1, harness.cryptoCount)
    }

    @Test
    fun `new secret direct conversation verifies crypto then creates and caches`() = runTest {
        val harness = Harness(created = directChat("created", "alice", chatType = "SECRET"))

        val result = harness.resolver.resolve(
            harness.request(activeContactId = "alice", createSecretConversation = true),
        )

        assertEquals("created", result.getOrThrow().conversation.id)
        assertEquals(listOf(true), harness.secretCreateFlags)
        assertEquals(1, harness.cryptoCount)
        assertEquals(listOf("created"), harness.cachedWrites.map(Chat::id))
    }

    @Test
    fun `bot direct conversation skips local signal initialization`() = runTest {
        val harness = Harness(
            created = directChat("bot-chat", "bot_helper"),
            botIds = setOf("bot_helper"),
        )

        val result = harness.resolver.resolve(harness.request(activeContactId = "bot_helper"))

        assertEquals("bot-chat", result.getOrThrow().conversation.id)
        assertEquals(0, harness.cryptoCount)
    }

    @Test
    fun `self recipient is rejected before creating conversation`() = runTest {
        val harness = Harness()

        val failure = harness.resolver.resolve(harness.request(activeContactId = "owner")).exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertEquals("self", failure.message)
        assertEquals(0, harness.createCount)
        assertEquals(0, harness.cryptoCount)
    }

    @Test
    fun `session switch after fetch cancels without caching foreign owner data`() = runTest {
        val harness = Harness(fetched = listOf(groupChat("group", revision = 3)))
        harness.sessionChecksBeforeInvalid = 1

        assertFailsWith<CancellationException> {
            harness.resolver.resolve(harness.request(activeConversationId = "group"))
        }

        assertTrue(harness.cachedWrites.isEmpty())
    }

    private class Harness(
        private val cached: Chat? = null,
        private val fetched: List<Chat> = emptyList(),
        private val created: Chat = directChat("created", "alice"),
        private val botIds: Set<String> = emptySet(),
    ) {
        var fetchCount = 0
        var createCount = 0
        var cryptoCount = 0
        var sessionChecksBeforeInvalid = Int.MAX_VALUE
        private var sessionChecks = 0
        val cachedWrites = mutableListOf<Chat>()
        val secretCreateFlags = mutableListOf<Boolean>()

        val resolver = OutgoingConversationResolver(
            getCachedConversation = { cached },
            fetchConversations = {
                fetchCount += 1
                fetched
            },
            createDirectConversation = { _, _, secret ->
                createCount += 1
                secretCreateFlags += secret
                created
            },
            cacheConversation = { cachedWrites += it },
            ensureLocalCryptoReady = { _, _ ->
                cryptoCount += 1
                true
            },
            isBotUserId = botIds::contains,
            isOwnerSessionCurrent = {
                sessionChecks += 1
                sessionChecks <= sessionChecksBeforeInvalid
            },
            errors = OutgoingConversationErrors(
                notLoggedIn = { "login" },
                recipientNotReady = { "recipient" },
                cannotSendToSelf = { "self" },
            ),
        )

        fun request(
            activeConversationId: String = "",
            paintedConversation: Chat? = null,
            activeContactId: String = "",
            createSecretConversation: Boolean = false,
        ) = OutgoingConversationRequest(
            ownerUserId = "owner",
            authToken = "token",
            activeConversationId = activeConversationId,
            constructorConversationId = "",
            paintedConversation = paintedConversation,
            activeContactId = activeContactId,
            createSecretConversation = createSecretConversation,
        )
    }

    private companion object {
        fun directChat(id: String, peerId: String, chatType: String = "DIRECT") = Chat(
            id = id,
            participants = listOf(User(id = "owner", name = "Owner"), User(id = peerId, name = peerId)),
            chatType = chatType,
        )

        fun groupChat(id: String, revision: Long) = Chat(
            id = id,
            participants = listOf(User(id = "owner", name = "Owner")),
            isGroup = true,
            chatType = "GROUP",
            memberRevision = revision,
        )
    }
}
