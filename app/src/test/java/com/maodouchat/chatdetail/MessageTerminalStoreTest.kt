package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
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
}
