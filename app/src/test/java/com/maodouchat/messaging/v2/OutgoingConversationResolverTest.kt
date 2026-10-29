package com.maodouchat.messaging.v2

import com.maodouchat.crypto.LocalCryptoNotReadyException
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

    @Test
    fun `domain resolve by peerAccountId succeeds and returns ResolvedConversation`() = runTest {
        val harness = Harness(created = directChat("direct-bob", "bob"))

        val resolved = harness.resolver.resolve(peerAccountId = "bob")

        assertEquals("direct-bob", resolved.conversationId)
        assertTrue(resolved.isCryptoReady)
    }

    @Test
    fun `findCachedDirectConversation resolves offline without network`() = runTest {
        val cachedDirect = directChat("cached-direct-alice", "alice")
        val harness = Harness(
            findCachedDirect = { if (it == "alice") cachedDirect else null },
        )

        val result = harness.resolver.resolve(harness.request(activeContactId = "alice"))

        val resolved = result.getOrThrow()
        assertEquals("cached-direct-alice", resolved.conversation.id)
        assertEquals("alice", resolved.peerUserId)
        assertEquals(0, harness.createCount)
        assertEquals(0, harness.fetchCount)
    }

    @Test
    fun `offline fallback creates direct conversation when network throws IOException`() = runTest {
        val offlineCreated = directChat("offline-direct-charlie", "charlie")
        var offlineCreatedCalls = 0
        val harness = Harness(
            networkErrorOnCreate = true,
            createOfflineDirect = { _, _, _ ->
                offlineCreatedCalls += 1
                offlineCreated
            },
        )

        val result = harness.resolver.resolve(harness.request(activeContactId = "charlie"))

        val resolved = result.getOrThrow()
        assertEquals("offline-direct-charlie", resolved.conversation.id)
        assertEquals(1, offlineCreatedCalls)
        assertEquals(listOf("offline-direct-charlie"), harness.cachedWrites.map(Chat::id))
    }

    @Test
    fun `non-io create failure is not masked by the offline fallback`() = runTest {
        var offlineCalls = 0
        val harness = Harness(
            createFailure = RuntimeException("500"),
            createOfflineDirect = { _, _, _ ->
                offlineCalls += 1
                directChat("offline", "charlie")
            },
        )

        val failure = harness.resolver.resolve(harness.request(activeContactId = "charlie")).exceptionOrNull()

        assertIs<RuntimeException>(failure)
        assertEquals("500", failure.message)
        assertEquals(0, offlineCalls)
        assertTrue(harness.cachedWrites.isEmpty())
        assertEquals(1, harness.createCount)
    }

    @Test
    fun `blank auth token is rejected before any network or crypto work`() = runTest {
        val harness = Harness(cached = directChat("cached", "alice"))

        val failure = harness.resolver.resolve(harness.request(authToken = "")).exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertEquals("login", failure.message)
        assertEquals(0, harness.fetchCount)
        assertEquals(0, harness.createCount)
        assertEquals(0, harness.cryptoCount)
    }

    @Test
    fun `fetch without a matching conversation fails without caching`() = runTest {
        val harness = Harness(fetched = listOf(directChat("other", "x")))

        val failure = harness.resolver.resolve(harness.request(activeConversationId = "group")).exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertEquals("recipient", failure.message)
        assertEquals(1, harness.fetchCount)
        assertTrue(harness.cachedWrites.isEmpty())
        assertEquals(0, harness.cryptoCount)
    }

    @Test
    fun `session invalid at entry cancels before any io`() = runTest {
        val harness = Harness(cached = directChat("cached", "alice"))
        harness.sessionChecksBeforeInvalid = 0

        assertFailsWith<CancellationException> {
            harness.resolver.resolve(harness.request(activeConversationId = "cached"))
        }

        assertEquals(0, harness.fetchCount)
        assertEquals(0, harness.createCount)
        assertEquals(0, harness.cryptoCount)
    }

    @Test
    fun `crypto not ready fails without creating a conversation`() = runTest {
        val harness = Harness(cryptoReady = false)

        val failure = harness.resolver.resolve(harness.request(activeContactId = "alice")).exceptionOrNull()

        assertIs<LocalCryptoNotReadyException>(failure)
        assertEquals(0, harness.createCount)
        assertTrue(harness.cachedWrites.isEmpty())
    }

    @Test
    fun `painted complete group conversation resolves without fetch`() = runTest {
        val harness = Harness()

        val result = harness.resolver.resolve(
            harness.request(
                activeConversationId = "group",
                paintedConversation = groupChat("group", revision = 5),
            ),
        )

        val resolved = result.getOrThrow()
        assertEquals("group", resolved.conversation.id)
        assertEquals(null, resolved.peerUserId)
        assertEquals(0, harness.fetchCount)
        assertEquals(1, harness.cryptoCount)
    }

    @Test
    fun `constructor conversation id is used when active id is blank`() = runTest {
        val harness = Harness(cached = groupChat("ctor-group", revision = 7))

        val result = harness.resolver.resolve(
            harness.request(constructorConversationId = "ctor-group"),
        )

        val resolved = result.getOrThrow()
        assertEquals("ctor-group", resolved.conversation.id)
        assertEquals(0, harness.fetchCount)
        assertEquals(1, harness.cryptoCount)
    }

    @Test
    fun `active conversation id takes precedence over constructor id`() = runTest {
        val harness = Harness()

        val result = harness.resolver.resolve(
            harness.request(
                activeConversationId = "active",
                constructorConversationId = "ctor",
                paintedConversation = groupChat("active", revision = 3),
            ),
        )

        // If the constructor id won, the painted chat would miss and the empty
        // fetch would fail with a recipient error instead of resolving.
        val resolved = result.getOrThrow()
        assertEquals("active", resolved.conversation.id)
        assertEquals(0, harness.fetchCount)
        assertEquals(0, harness.createCount)
    }

    @Test
    fun `domain resolve for bot peer skips crypto`() = runTest {
        val harness = Harness(
            created = directChat("bot-chat", "bot_helper"),
            botIds = setOf("bot_helper"),
        )

        val resolved = harness.resolver.resolve(peerAccountId = "bot_helper")

        assertEquals("bot-chat", resolved.conversationId)
        assertTrue(resolved.isCryptoReady)
        assertEquals(0, harness.cryptoCount)
    }

    @Test
    fun `session switch after create cancels before caching`() = runTest {
        val harness = Harness()
        harness.sessionChecksBeforeInvalid = 2

        assertFailsWith<CancellationException> {
            harness.resolver.resolve(harness.request(activeContactId = "alice"))
        }

        assertEquals(1, harness.createCount)
        assertEquals(1, harness.cryptoCount)
        assertTrue(harness.cachedWrites.isEmpty())
    }

    private class Harness(
        private val cached: Chat? = null,
        private val fetched: List<Chat> = emptyList(),
        private val created: Chat = directChat("created", "alice"),
        private val botIds: Set<String> = emptySet(),
        private val networkErrorOnCreate: Boolean = false,
        private val createFailure: Throwable? = null,
        private val cryptoReady: Boolean = true,
        private val findCachedDirect: (suspend (String) -> Chat?)? = null,
        private val createOfflineDirect: (suspend (String, String, Boolean) -> Chat)? = null,
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
                createFailure?.let { throw it }
                if (networkErrorOnCreate) throw java.io.IOException("offline")
                secretCreateFlags += secret
                created
            },
            cacheConversation = { cachedWrites += it },
            ensureLocalCryptoReady = { _, _ ->
                cryptoCount += 1
                cryptoReady
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
            currentOwnerUserId = { "owner" },
            currentAuthToken = { "token" },
            findCachedDirectConversation = findCachedDirect,
            createOfflineDirectConversation = createOfflineDirect,
        )

        fun request(
            activeConversationId: String = "",
            constructorConversationId: String = "",
            paintedConversation: Chat? = null,
            activeContactId: String = "",
            createSecretConversation: Boolean = false,
            authToken: String = "token",
        ) = OutgoingConversationRequest(
            ownerUserId = "owner",
            authToken = authToken,
            activeConversationId = activeConversationId,
            constructorConversationId = constructorConversationId,
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
