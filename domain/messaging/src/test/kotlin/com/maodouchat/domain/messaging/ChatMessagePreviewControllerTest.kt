package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

private val me = User(id = "owner-1", name = "Me")
private val peer = User(id = "u2", name = "U2")
private val directChat = Chat(id = "c1", participants = listOf(me, peer))
private val groupChat = Chat(id = "c1", participants = listOf(me, peer), isGroup = true)

class ChatMessagePreviewControllerTest {

    private data class Emitted(
        val chatId: String,
        val preview: String,
        val typeName: String,
        val playSendSound: Boolean,
    )

    private class Harness(
        var chat: Chat? = directChat,
        var contact: User = peer,
        var currentUserId: String = "owner-1",
        var activeChatId: String = "c1",
        var searchAllowed: Boolean = true,
        var indexError: Throwable? = null,
    ) {
        val emitted = mutableListOf<Emitted>()
        val indexed = mutableListOf<Message>()
        val warnings = mutableListOf<Pair<String, Throwable?>>()

        val controller = ChatMessagePreviewController(
            chatProvider = { chat },
            contactProvider = { contact },
            currentUserId = { currentUserId },
            activeChatId = { activeChatId },
            textProvider = { kind, args -> "text:${kind.name}(${args.joinToString(",")})" },
            emitListPreview = { chatId, preview, typeName, playSendSound ->
                emitted += Emitted(chatId, preview, typeName, playSendSound)
            },
            allowsSearchIndex = { searchAllowed },
            indexMessage = { message ->
                indexError?.let { throw it }
                indexed += message
            },
            logWarning = { message, error -> warnings += message to error },
        )
    }

    private fun message(
        id: String = "m1",
        chatId: String = "c1",
        senderId: String = "owner-1",
        content: String = "hello",
        type: MessageType = MessageType.TEXT,
    ) = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = content,
        type = type,
        timestamp = 1L,
    )

    @Test
    fun textMessageEmitsPlainBody() {
        val harness = Harness()
        harness.controller.emitListPreviewForDecrypted(message())
        assertEquals(listOf(Emitted("c1", "hello", "TEXT", false)), harness.emitted)
    }

    @Test
    fun textMessageTruncatesAt200() {
        val harness = Harness()
        val longBody = "x".repeat(250)
        harness.controller.emitListPreviewForDecrypted(message(content = longBody))
        assertEquals("x".repeat(200), harness.emitted.single().preview)
    }

    @Test
    fun textMessageWithWireEnvelopeEmitsEncryptedPlaceholder() {
        val harness = Harness()
        harness.controller.emitListPreviewForDecrypted(message(content = "{\"ciphertext\":\"abc\"}"))
        assertEquals("text:PREVIEW_ENCRYPTED()", harness.emitted.single().preview)
    }

    @Test
    fun blankTextMessageEmitsEncryptedPlaceholder() {
        val harness = Harness()
        harness.controller.emitListPreviewForDecrypted(message(content = "   "))
        assertEquals("text:PREVIEW_ENCRYPTED()", harness.emitted.single().preview)
    }

    @Test
    fun markdownMessageEmitsPlainBody() {
        val harness = Harness()
        harness.controller.emitListPreviewForDecrypted(message(content = "hi", type = MessageType.MARKDOWN))
        assertEquals("hi", harness.emitted.single().preview)
    }

    @Test
    fun imageMessageEmitsMediaLabel() {
        val harness = Harness()
        harness.controller.emitListPreviewForDecrypted(message(type = MessageType.IMAGE))
        assertEquals("text:PREVIEW_IMAGE()", harness.emitted.single().preview)
    }

    @Test
    fun fileMessageEmitsMediaLabel() {
        val harness = Harness()
        harness.controller.emitListPreviewForDecrypted(message(type = MessageType.FILE))
        assertEquals("text:PREVIEW_FILE()", harness.emitted.single().preview)
    }

    @Test
    fun revokedMessageEmitsRevokedPlaceholder() {
        val harness = Harness()
        harness.controller.emitListPreviewForDecrypted(message(type = MessageType.REVOKED))
        assertEquals("text:MESSAGE_REVOKED_PLACEHOLDER()", harness.emitted.single().preview)
    }

    @Test
    fun nudgeFromSelfUsesYouNudgedTemplate() {
        val harness = Harness()
        harness.controller.emitListPreviewForDecrypted(
            message(senderId = "owner-1", content = "你拍了拍U2", type = MessageType.NUDGE)
        )
        assertEquals("text:NUDGE_YOU_NUDGED(U2)", harness.emitted.single().preview)
    }

    @Test
    fun nudgeFromPeerInDirectChatUsesTheyNudgedYou() {
        val harness = Harness()
        harness.controller.emitListPreviewForDecrypted(
            message(senderId = "u2", content = "你拍了拍Me", type = MessageType.NUDGE)
        )
        assertEquals("text:NUDGE_THEY_NUDGED_YOU(U2)", harness.emitted.single().preview)
    }

    @Test
    fun nudgeFromPeerInGroupUsesTheyNudgedTarget() {
        val harness = Harness(chat = groupChat)
        harness.controller.emitListPreviewForDecrypted(
            message(senderId = "u2", content = "你拍了拍Me", type = MessageType.NUDGE)
        )
        assertEquals("text:NUDGE_THEY_NUDGED_TARGET(U2,Me)", harness.emitted.single().preview)
    }

    @Test
    fun skDistMessageEmitsNothing() {
        val harness = Harness()
        harness.controller.emitListPreviewForDecrypted(message(type = MessageType.SK_DIST))
        assertTrue(harness.emitted.isEmpty())
    }

    @Test
    fun blankChatIdFallsBackToActiveChatId() {
        val harness = Harness(activeChatId = "c9")
        harness.controller.emitListPreviewForDecrypted(message(chatId = ""))
        assertEquals("c9", harness.emitted.single().chatId)
    }

    @Test
    fun blankChatIdAndActiveChatIdEmitsNothing() {
        val harness = Harness(activeChatId = "")
        harness.controller.emitListPreviewForDecrypted(message(chatId = ""))
        assertTrue(harness.emitted.isEmpty())
    }

    @Test
    fun indexSearchableMessageSkippedWhenNotAllowed() = runTest {
        val harness = Harness(searchAllowed = false)
        harness.controller.indexSearchableMessage(message())
        assertTrue(harness.indexed.isEmpty())
    }

    @Test
    fun indexSearchableMessageIndexesWhenAllowed() = runTest {
        val harness = Harness()
        val input = message()
        harness.controller.indexSearchableMessage(input)
        assertEquals(listOf(input), harness.indexed)
    }

    @Test
    fun indexSearchableMessageSwallowsIndexFailure() = runTest {
        val harness = Harness(indexError = RuntimeException("boom"))
        harness.controller.indexSearchableMessage(message())
        assertEquals(listOf("indexSearchableMessage failed"), harness.warnings.map { it.first })
    }

    @Test
    fun indexSearchableMessageRethrowsCancellation() = runTest {
        val harness = Harness(indexError = CancellationException("cancelled"))
        assertFailsWith<CancellationException> {
            harness.controller.indexSearchableMessage(message())
        }
    }
}
