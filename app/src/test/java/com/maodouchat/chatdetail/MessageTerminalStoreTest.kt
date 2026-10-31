package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.MessageTerminalStore
import com.maodouchat.ui.screen.chatdetail.toRevokedPlaceholder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTerminalStoreTest {
    @Test
    fun `delete removes media before room row`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistDeleted("message-1")

        assertEquals(listOf("media:message-1", "search:message-1", "delete:message-1"), effects)
    }

    @Test
    fun `revoke clears media even when original message is unavailable`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistRevoked("message-1", null)

        assertEquals(listOf("media:message-1", "search:message-1"), effects)
    }

    @Test
    fun `revoked placeholder destroys content metadata and persists`() = runTest {
        val persisted = mutableListOf<Message>()
        val store = MessageTerminalStore({}, {}, {}, persisted::add)
        val original = Message(
            id = "message-1",
            chatId = "chat-1",
            senderId = "user-1",
            content = "secret<meta>{}</meta>",
            type = MessageType.IMAGE,
            meta = MessageMeta(
                translations = mapOf("中文" to "秘密"),
                attachmentKeyBase64 = "secret-key"
            )
        )
        val revoked = original.toRevokedPlaceholder("revoked")

        store.persistRevoked(original.id, revoked)

        assertEquals(MessageType.REVOKED, persisted.single().type)
        assertEquals("revoked", persisted.single().content)
        assertTrue(persisted.single().meta.attachmentKeyBase64 == null)
        assertTrue(persisted.single().meta.translations.isEmpty())
    }

    private fun sampleMessage() = Message(
        id = "message-1",
        chatId = "chat-1",
        senderId = "user-1",
        content = "hi",
        type = MessageType.TEXT,
        meta = MessageMeta()
    )

    @Test
    fun `blank message ids are no-ops`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistDeleted("")
        store.persistRevoked("   ", sampleMessage())

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `delete continues past a cache failure and rethrows the first error`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { throw IllegalStateException("cache") },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        // 剩余步骤照样执行，首错原样抛出
        assertEquals(listOf("search:message-1", "delete:message-1"), effects)
        assertEquals("cache", (failure as? IllegalStateException)?.message)
    }

    @Test
    fun `later failures are suppressed under the first`() = runTest {
        val store = MessageTerminalStore(
            deleteCachedMedia = {},
            deleteSearchDocument = { throw IllegalStateException("search") },
            deleteLocalMessage = { throw IllegalStateException("delete") },
            upsertLocalMessage = {}
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        assertEquals("search", failure?.message)
        assertEquals(listOf("delete"), failure?.suppressed?.map { it.message })
    }

    @Test
    fun `revoke upserts the placeholder after cleanup`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistRevoked("message-1", sampleMessage())

        assertEquals(listOf("media:message-1", "search:message-1", "upsert:message-1"), effects)
    }

    @Test
    fun `revoke still runs cleanup when the upsert fails`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { throw IllegalStateException("upsert") }
        )

        val failure = runCatching { store.persistRevoked("message-1", sampleMessage()) }.exceptionOrNull()

        assertEquals(listOf("media:message-1", "search:message-1"), effects)
        assertEquals("upsert", failure?.message)
    }

    @Test
    fun `revoke keeps the first cleanup error when the upsert also fails`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { throw IllegalStateException("cache") },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { throw IllegalStateException("upsert") }
        )

        val failure = runCatching { store.persistRevoked("message-1", sampleMessage()) }.exceptionOrNull()

        assertEquals(listOf("search:message-1"), effects)
        assertEquals("cache", failure?.message)
        assertEquals(listOf("upsert"), failure?.suppressed?.map { it.message })
    }

    @Test
    fun `delete surfaces a late failure after earlier steps ran`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { throw IllegalStateException("delete") },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        assertEquals(listOf("media:message-1", "search:message-1"), effects)
        assertEquals("delete", failure?.message)
        assertTrue(failure?.suppressed.isNullOrEmpty())
    }

    @Test
    fun `repeated delete runs the full cleanup each time`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = {}
        )

        store.persistDeleted("message-1")
        store.persistDeleted("message-1")

        assertEquals(
            listOf(
                "media:message-1", "search:message-1", "delete:message-1",
                "media:message-1", "search:message-1", "delete:message-1"
            ),
            effects
        )
    }

    @Test
    fun `revoked placeholder keeps identity but wipes metadata`() = runTest {
        val persisted = mutableListOf<Message>()
        val store = MessageTerminalStore({}, {}, {}, persisted::add)
        val original = sampleMessage().copy(
            content = "secret",
            type = MessageType.IMAGE,
            meta = MessageMeta(translations = mapOf("a" to "b"))
        )

        store.persistRevoked(original.id, original.toRevokedPlaceholder("已撤回"))

        val saved = persisted.single()
        assertEquals("message-1", saved.id)
        assertEquals("chat-1", saved.chatId)
        assertEquals("user-1", saved.senderId)
        assertEquals("已撤回", saved.content)
        assertEquals(MessageType.REVOKED, saved.type)
        assertTrue(saved.meta.translations.isEmpty())
    }

    @Test
    fun `fatal errors bypass suppression and propagate immediately`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { throw AssertionError("fatal") },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = {}
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        assertTrue(failure is AssertionError)
        assertTrue(effects.isEmpty())
    }
    @Test
    fun `delete continues past a search failure to the local row`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { throw IllegalStateException("search") },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        assertEquals(listOf("media:message-1", "delete:message-1"), effects)
        assertEquals("search", failure?.message)
        assertTrue(failure?.suppressed.isNullOrEmpty())
    }

    @Test
    fun `revoke still upserts when the search step fails`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { throw IllegalStateException("search") },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        val failure = runCatching { store.persistRevoked("message-1", sampleMessage()) }.exceptionOrNull()

        assertEquals(listOf("media:message-1", "upsert:message-1"), effects)
        assertEquals("search", failure?.message)
    }

    @Test
    fun `delete failing at every step reports first and suppresses rest`() = runTest {
        val store = MessageTerminalStore(
            deleteCachedMedia = { throw IllegalStateException("cache") },
            deleteSearchDocument = { throw IllegalStateException("search") },
            deleteLocalMessage = { throw IllegalStateException("delete") },
            upsertLocalMessage = {}
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        assertEquals("cache", failure?.message)
        assertEquals(listOf("search", "delete"), failure?.suppressed?.map { it.message })
    }

    @Test
    fun `revoke without placeholder still reports a search failure`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { throw IllegalStateException("search") },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        val failure = runCatching { store.persistRevoked("message-1", null) }.exceptionOrNull()

        assertEquals(listOf("media:message-1"), effects)
        assertEquals("search", failure?.message)
    }

    @Test
    fun `fatal error in revoke path bypasses suppression immediately`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { throw AssertionError("fatal") },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        val failure = runCatching { store.persistRevoked("message-1", sampleMessage()) }.exceptionOrNull()

        assertTrue(failure is AssertionError)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `delete with blank id skips every step`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistDeleted("   ")
        store.persistRevoked("", sampleMessage())

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `revoke upserts a mismatched placeholder id verbatim`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistRevoked("message-1", sampleMessage().copy(id = "other"))

        assertEquals(listOf("media:message-1", "search:message-1", "upsert:other"), effects)
    }

    @Test
    fun `delete never calls upsert`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { throw IllegalStateException("upsert") }
        )

        store.persistDeleted("message-1")

        assertEquals(listOf("media:message-1", "search:message-1", "delete:message-1"), effects)
    }

    @Test
    fun `revoke without placeholder never touches the local row delete`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { throw IllegalStateException("delete") },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistRevoked("message-1", null)

        assertEquals(listOf("media:message-1", "search:message-1"), effects)
    }

    @Test
    fun `revoke failing at media and search reports first and still upserts`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { throw IllegalStateException("cache") },
            deleteSearchDocument = { throw IllegalStateException("search") },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        val failure = runCatching { store.persistRevoked("message-1", sampleMessage()) }.exceptionOrNull()

        assertEquals(listOf("upsert:message-1"), effects)
        assertEquals("cache", failure?.message)
        assertEquals(listOf("search"), failure?.suppressed?.map { it.message })
    }

    @Test
    fun `revoke with placeholder never calls the local row delete`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { throw IllegalStateException("delete") },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistRevoked("message-1", sampleMessage())

        assertEquals(listOf("media:message-1", "search:message-1", "upsert:message-1"), effects)
    }

    @Test
    fun `delete with media and search failures reports first and suppresses second`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { throw IllegalStateException("cache") },
            deleteSearchDocument = { throw IllegalStateException("search") },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = {}
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        assertEquals(listOf("delete:message-1"), effects)
        assertEquals("cache", failure?.message)
        assertEquals(listOf("search"), failure?.suppressed?.map { it.message })
    }

    @Test
    fun `tab and newline ids are blank and skip every step`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistDeleted("\t")
        store.persistRevoked("\t\n", sampleMessage())

        assertTrue(effects.isEmpty())
    }

    @Test
    fun `delete failure never reaches upsert`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { throw IllegalStateException("delete") },
            upsertLocalMessage = { throw IllegalStateException("upsert") }
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        assertEquals(listOf("media:message-1", "search:message-1"), effects)
        assertEquals("delete", failure?.message)
        assertTrue(failure?.suppressed.isNullOrEmpty())
    }

    @Test
    fun `repeated revoke runs the full cleanup and upsert each time`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistRevoked("message-1", sampleMessage())
        store.persistRevoked("message-1", sampleMessage())

        assertEquals(
            listOf(
                "media:message-1", "search:message-1", "upsert:message-1",
                "media:message-1", "search:message-1", "upsert:message-1"
            ),
            effects
        )
    }

    @Test
    fun `delete with fatal error in the local row step keeps earlier effects`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { throw AssertionError("fatal") },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        assertEquals(listOf("media:message-1", "search:message-1"), effects)
        assertTrue(failure is AssertionError)
    }

    @Test
    fun `revoke with fatal error in the search step skips the upsert`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { throw AssertionError("fatal") },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        val failure = runCatching { store.persistRevoked("message-1", sampleMessage()) }.exceptionOrNull()

        assertEquals(listOf("media:message-1"), effects)
        assertTrue(failure is AssertionError)
    }

    @Test
    fun `revoke with fatal error in the upsert keeps cleanup effects`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { throw AssertionError("fatal") }
        )

        val failure = runCatching { store.persistRevoked("message-1", sampleMessage()) }.exceptionOrNull()

        assertEquals(listOf("media:message-1", "search:message-1"), effects)
        assertTrue(failure is AssertionError)
    }

    @Test
    fun `revoked placeholder preserves timestamp status and starred flag`() = runTest {
        val persisted = mutableListOf<Message>()
        val store = MessageTerminalStore({}, {}, {}, persisted::add)
        val original = Message(
            id = "message-1",
            chatId = "chat-1",
            senderId = "user-1",
            content = "secret",
            type = MessageType.IMAGE,
            timestamp = 4242L,
            status = MessageStatus.READ,
            starred = true,
            meta = MessageMeta(
                translations = mapOf("a" to "b"),
                attachmentKeyBase64 = "secret-key"
            )
        )

        store.persistRevoked(original.id, original.toRevokedPlaceholder("已撤回"))

        val saved = persisted.single()
        assertEquals(4242L, saved.timestamp)
        assertEquals(MessageStatus.READ, saved.status)
        assertTrue(saved.starred)
        assertEquals("已撤回", saved.content)
        assertEquals(MessageType.REVOKED, saved.type)
        assertTrue(saved.meta.translations.isEmpty())
        assertTrue(saved.meta.attachmentKeyBase64 == null)
    }

    @Test
    fun `revoke media error then fatal upsert propagates the fatal immediately`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { throw IllegalStateException("cache") },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { throw AssertionError("upsert-fatal") }
        )

        val failure = runCatching { store.persistRevoked("message-1", sampleMessage()) }.exceptionOrNull()

        assertEquals(listOf("search:message-1"), effects)
        assertTrue(failure is AssertionError)
        assertEquals("upsert-fatal", failure?.message)
    }
    @Test
    fun `delete with padded id runs every step verbatim`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistDeleted(" message-1 ")

        assertEquals(
            listOf("media: message-1 ", "search: message-1 ", "delete: message-1 "),
            effects
        )
    }

    @Test
    fun `revoke with padded id cleans verbatim and upserts the placeholder`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { effects += "search:$it" },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        store.persistRevoked(" message-1 ", sampleMessage())

        assertEquals(
            listOf("media: message-1 ", "search: message-1 ", "upsert:message-1"),
            effects
        )
    }

    @Test
    fun `delete reports search failure first and suppresses local row failure`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { throw IllegalStateException("search") },
            deleteLocalMessage = { throw IllegalStateException("local") },
            upsertLocalMessage = { effects += "upsert:${it.id}" }
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        assertEquals(listOf("media:message-1"), effects)
        assertEquals("search", failure?.message)
        assertEquals(listOf("local"), failure?.suppressed?.map { it.message })
    }

    @Test
    fun `revoke reports search failure first and suppresses upsert failure`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { throw IllegalStateException("search") },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = { throw IllegalStateException("upsert") }
        )

        val failure = runCatching { store.persistRevoked("message-1", sampleMessage()) }.exceptionOrNull()

        assertEquals(listOf("media:message-1"), effects)
        assertEquals("search", failure?.message)
        assertEquals(listOf("upsert"), failure?.suppressed?.map { it.message })
    }

    @Test
    fun `delete with fatal error in the search step skips the local row`() = runTest {
        val effects = mutableListOf<String>()
        val store = MessageTerminalStore(
            deleteCachedMedia = { effects += "media:$it" },
            deleteSearchDocument = { throw AssertionError("fatal-search") },
            deleteLocalMessage = { effects += "delete:$it" },
            upsertLocalMessage = {}
        )

        val failure = runCatching { store.persistDeleted("message-1") }.exceptionOrNull()

        assertTrue(failure is AssertionError)
        assertEquals(listOf("media:message-1"), effects)
    }

}
