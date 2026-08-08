package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

/**
 * Pure helpers for chat-list last-message preview after send / delete / revoke / edit.
 * Localized media labels are supplied by the caller (string resources live in the VM/UI).
 */
object ChatListPreviewPolicy {

    data class Preview(
        val text: String,
        val type: MessageType,
        val timestamp: Long
    )

    /**
     * Build list preview from the newest conversation-visible local row.
     * [candidatesNewestFirst] should be newest-first (e.g. getRecentMessages).
     * SK_DIST rows are skipped so crypto control never becomes the list head.
     * Empty chat → blank TEXT @ 0 so absolute writers can clear stale previews.
     */
    fun fromLatestMessages(
        candidatesNewestFirst: List<Message>,
        mediaLabel: (MessageType) -> String,
        encryptedPlaceholder: String,
        revokedPlaceholder: String,
        /** Optional POV rewrite for NUDGE (server stores sender-centric body). */
        nudgeText: ((Message) -> String)? = null
    ): Preview {
        val head = candidatesNewestFirst.firstOrNull { !isListPreviewNoise(it.type) }
        return fromLatestMessage(head, mediaLabel, encryptedPlaceholder, revokedPlaceholder, nudgeText)
    }

    fun fromLatestMessage(
        latest: Message?,
        mediaLabel: (MessageType) -> String,
        encryptedPlaceholder: String,
        revokedPlaceholder: String,
        /** Optional POV rewrite for NUDGE (server stores sender-centric body). */
        nudgeText: ((Message) -> String)? = null
    ): Preview {
        if (latest == null || isListPreviewNoise(latest.type)) {
            return Preview(text = "", type = MessageType.TEXT, timestamp = 0L)
        }
        val text = when (latest.type) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.LOCATION,
            MessageType.VOICE,
            MessageType.VIDEO,
            MessageType.FILE -> mediaLabel(latest.type)
            MessageType.NUDGE -> nudgeText?.invoke(latest) ?: latest.content
            MessageType.REVOKED -> revokedPlaceholder
            MessageType.SYSTEM -> latest.content
            MessageType.SK_DIST -> ""
            MessageType.TEXT, MessageType.MARKDOWN -> {
                val c = latest.content
                if (c.isBlank() || looksLikeWireEnvelope(c)) encryptedPlaceholder else c
            }
        }
        return Preview(
            text = text,
            type = latest.type,
            timestamp = latest.timestamp
        )
    }

    /**
     * Whether mutating [mutatedMessageId] can change the list head preview.
     * Unknown head (null/blank) is treated as "maybe" so callers refresh safely.
     */
    fun affectsListHead(headMessageId: String?, mutatedMessageId: String): Boolean {
        if (mutatedMessageId.isBlank()) return false
        if (headMessageId.isNullOrBlank()) return true
        return headMessageId == mutatedMessageId
    }

    /** Crypto control / non-conversation rows must not drive list preview or unread. */
    fun isListPreviewNoise(type: MessageType): Boolean =
        type == MessageType.SK_DIST

    /**
     * Heuristic for Signal JSON envelopes (object or multi-device array).
     * Media list labels like `[图片]` / `[GIF]` start with `[` but are not wire —
     * only treat as wire when the body looks like JSON structure.
     */
    fun looksLikeWireEnvelope(content: String): Boolean {
        val t = content.trimStart()
        if (t.startsWith("{")) return true
        // Multi-device / JSON array envelopes start with `[` then `{` or `"`.
        // Localized labels are `[...]` without nested JSON.
        if (t.startsWith("[{") || t.startsWith("[\"")) return true
        return false
    }

    /**
     * Same-message local echo: Room already holds a human-readable body for this message id
     * (plaintext TEXT after local send). Prefer that over WS ciphertext so emitMessageSent
     * plaintext is not overwritten.
     *
     * Must NOT key only on chat-list lastMessage — that is the *previous* head and would
     * freeze multi-device new sends on the old tail.
     */
    fun shouldKeepExistingOwnPreview(
        isOwnMessage: Boolean,
        messageType: MessageType,
        existingSameMessageContent: String?,
        encryptedPlaceholder: String
    ): Boolean {
        if (!isOwnMessage) return false
        if (messageType !in OWN_ECHO_PREVIEW_TYPES) return false
        val body = existingSameMessageContent?.takeIf { it.isNotBlank() } ?: return false
        if (looksLikeWireEnvelope(body)) return false
        if (body == encryptedPlaceholder) return false
        return true
    }

    /**
     * List-row tail for own same-message echo when [shouldKeepExistingOwnPreview] is true.
     * TEXT uses Room plaintext (truncated); media types use localized labels.
     */
    fun ownEchoListPreview(
        messageType: MessageType,
        sameMessagePlainOrLabel: String,
        existingListPreview: String?,
        mediaLabel: (MessageType) -> String,
        maxLen: Int = 200
    ): String {
        return when (messageType) {
            MessageType.TEXT, MessageType.MARKDOWN -> sameMessagePlainOrLabel.take(maxLen)
            MessageType.NUDGE -> sameMessagePlainOrLabel.takeIf { it.isNotBlank() }
                ?: existingListPreview?.takeIf { it.isNotBlank() }
                ?: sameMessagePlainOrLabel
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.LOCATION,
            MessageType.VOICE,
            MessageType.VIDEO,
            MessageType.FILE -> mediaLabel(messageType)
            else -> sameMessagePlainOrLabel.take(maxLen)
        }
    }

    /**
     * TEXT list/notify body: prefer decrypted plaintext; never surface wire JSON.
     */
    fun textPreviewFromPlainOrEncrypted(
        decryptedPlain: String?,
        encryptedPlaceholder: String,
        maxLen: Int = 200
    ): String {
        val body = decryptedPlain?.takeIf { it.isNotBlank() && !looksLikeWireEnvelope(it) }
        return body?.take(maxLen) ?: encryptedPlaceholder
    }

    private val OWN_ECHO_PREVIEW_TYPES = setOf(
        MessageType.TEXT,
        MessageType.MARKDOWN,
        MessageType.STICKER,
        MessageType.LOCATION,
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.VOICE,
        MessageType.VIDEO,
        MessageType.FILE,
        MessageType.NUDGE
    )
}
