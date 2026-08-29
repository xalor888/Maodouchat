package com.maodouchat.forwarding

import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.JsonFormat
import kotlinx.coroutines.CancellationException
import java.util.UUID

data class ForwardBatchResult(
    val forwardedCount: Int,
    val failedCount: Int,
    val firstError: Throwable? = null,
    val attachmentFailed: Boolean = false,
)

class ConversationForwardSessionException(message: String) : IllegalStateException(message)
class ConversationForwardUnsupportedException(val messageType: MessageType) :
    IllegalArgumentException("forward_type_unsupported:$messageType")

/**
 * Owns forwarding order and the durable commit boundary.
 *
 * Text-like messages are complete once [stageMessage] returns. UI projection,
 * list preview, sounds, and other post-commit work may fail but cannot turn the
 * durable message into FAILED or cause a retry of the same forward.
 */
class ConversationForwardCoordinator(
    private val ownerUserId: () -> String,
    private val token: () -> String,
    private val sessionActive: (ownerUserId: String) -> Boolean,
    private val fetchTargets: suspend (token: String) -> Result<List<Chat>>,
    private val resolveTargets: suspend (token: String, targets: List<Chat>) -> List<Chat> = { _, targets -> targets },
    private val stageMessage: suspend (message: Message, groupRevision: Long?) -> Unit,
    private val forwardAttachment: suspend (
        target: Chat,
        message: Message,
        messageId: String,
        sourceName: String?,
        ownerUserId: String,
    ) -> Unit,
    private val onDurableMessage: (Message) -> Unit = {},
    private val onMessageSent: (chatId: String, preview: String, type: MessageType) -> Unit = { _, _, _ -> },
    private val preview: (type: MessageType, plainContent: String) -> String = { _, content -> content.take(40) },
    private val messageId: () -> String = { "m_${UUID.randomUUID()}" },
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun loadTargets(activeChatId: String): List<Chat> {
        val owner = requireSession()
        val liveToken = token().trim()
        val targets = fetchTargets(liveToken).getOrThrow()
        ensureSession(owner)
        return targets
            .filter { it.id != activeChatId && !it.archived }
            .sortedWith(
                compareByDescending<Chat> { it.pinnedAt > 0L }
                    .thenByDescending { it.pinnedAt }
                    .thenByDescending { it.lastMessageTime },
            )
    }

    suspend fun sendNote(target: Chat, text: String): Message {
        val owner = requireSession()
        val resolvedTarget = resolveCurrentTargets(owner, listOf(target)).single()
        return sendNoteResolved(owner, resolvedTarget, text)
    }

    private suspend fun sendNoteResolved(owner: String, target: Chat, text: String): Message {
        ensureSession(owner)
        val normalized = text.trim()
        require(normalized.isNotBlank()) { "forward_note_empty" }
        val markdown = com.maodouchat.ui.component.ChatMarkdown.looksLikeMarkdown(normalized)
        val type = if (markdown) MessageType.MARKDOWN else MessageType.TEXT
        val message = Message(
            id = messageId(),
            chatId = target.id,
            senderId = owner,
            content = normalized,
            type = type,
            timestamp = now(),
            status = MessageStatus.SENDING,
            meta = MessageMeta(markdown = markdown),
        )
        stageAndPublish(target, message, normalized.take(40))
        return message
    }

    suspend fun forward(
        target: Chat,
        message: Message,
        sourceName: String?,
    ): Message? {
        val owner = requireSession()
        val resolvedTarget = resolveCurrentTargets(owner, listOf(target)).single()
        return forwardResolved(owner, resolvedTarget, message, sourceName)
    }

    private suspend fun forwardResolved(
        owner: String,
        target: Chat,
        message: Message,
        sourceName: String?,
    ): Message? {
        ensureSession(owner)
        ensureForwardable(message.type)
        val id = messageId()
        if (message.type in ATTACHMENT_TYPES) {
            forwardAttachment(target, message, id, sourceName, owner)
            return null
        }

        val plainContent = message.parsedContent()
        val existingMeta = message.parsedMeta()
        val forwardMeta = existingMeta.copy(
            forwardedFrom = existingMeta.forwardedFrom ?: sourceName,
        )
        val local = Message(
            id = id,
            chatId = target.id,
            senderId = owner,
            content = JsonFormat.composeContentWithMeta(plainContent, forwardMeta),
            type = message.type,
            timestamp = now(),
            status = MessageStatus.SENDING,
            meta = forwardMeta,
        )
        stageAndPublish(target, local, preview(message.type, plainContent))
        return local
    }

    suspend fun forwardBatch(
        targets: List<Chat>,
        messages: List<Message>,
        note: String?,
        sourceName: (Message) -> String?,
    ): ForwardBatchResult {
        val owner = requireSession()
        val usable = messages.filter { it.type in FORWARDABLE_TYPES }
        var forwardedCount = 0
        var failedCount = 0
        var firstError: Throwable? = null
        var attachmentFailed = false
        val normalizedNote = note?.trim()?.takeIf(String::isNotBlank)
        val resolvedTargets = resolveCurrentTargets(owner, targets.distinctBy(Chat::id))

        for (target in resolvedTargets) {
            ensureSession(owner)
            var targetHadError = false
            for (message in usable) {
                try {
                    forwardResolved(owner, target, message, sourceName(message))
                    forwardedCount += 1
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    targetHadError = true
                    failedCount += 1
                    if (firstError == null) firstError = error
                    if (message.type in ATTACHMENT_TYPES) attachmentFailed = true
                }
            }
            if (normalizedNote != null && !targetHadError) {
                try {
                    sendNoteResolved(owner, target, normalizedNote)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // The forwarded messages are already durable. A note failure is independent.
                    if (firstError == null) firstError = error
                }
            }
        }
        return ForwardBatchResult(
            forwardedCount = forwardedCount,
            failedCount = failedCount,
            firstError = firstError,
            attachmentFailed = attachmentFailed,
        )
    }

    private suspend fun stageAndPublish(target: Chat, message: Message, preview: String) {
        val owner = message.senderId
        ensureSession(owner)
        stageMessage(
            message,
            target.memberRevision.takeIf { target.isGroup && it > 0L },
        )
        onDurableMessage(message)
        runCatching { onMessageSent(target.id, preview, message.type) }
    }

    private suspend fun resolveCurrentTargets(owner: String, targets: List<Chat>): List<Chat> {
        ensureSession(owner)
        val resolved = resolveTargets(token().trim(), targets)
        ensureSession(owner)
        val requestedIds = targets.map(Chat::id).toSet()
        val resolvedById = resolved.associateBy(Chat::id)
        return targets.map { requested ->
            val live = resolvedById[requested.id]
                ?: throw IllegalStateException("forward_target_missing:${requested.id}")
            if (live.isGroup && live.memberRevision <= 0L) {
                throw IllegalStateException("group_epoch_unknown:${live.id}")
            }
            live
        }.also {
            check(it.map(Chat::id).toSet() == requestedIds) { "forward_target_resolution_mismatch" }
        }
    }

    private fun requireSession(): String {
        val owner = ownerUserId().trim()
        if (owner.isBlank() || token().isBlank() || !sessionActive(owner)) {
            throw ConversationForwardSessionException("forward_session_missing")
        }
        return owner
    }

    private fun ensureSession(owner: String) {
        if (!sessionActive(owner)) {
            throw CancellationException("forward_session_changed")
        }
    }

    private fun ensureForwardable(type: MessageType) {
        if (type !in FORWARDABLE_TYPES) throw ConversationForwardUnsupportedException(type)
    }

    companion object {
        val ATTACHMENT_TYPES = setOf(
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.VIDEO,
            MessageType.VOICE,
            MessageType.FILE,
        )
        val FORWARDABLE_TYPES = ATTACHMENT_TYPES + setOf(
            MessageType.TEXT,
            MessageType.MARKDOWN,
            MessageType.STICKER,
            MessageType.LOCATION,
        )
    }
}
