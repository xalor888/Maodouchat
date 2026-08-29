package com.maodouchat.data.repository

import com.maodouchat.crypto.DecryptPlaceholderPolicy
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
        val head = candidatesNewestFirst.firstOrNull { candidate ->
            !isListPreviewNoise(candidate.type) && !isUnreadableListHead(candidate)
        }
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
                listVisibleText(latest.content, encryptedPlaceholder)
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
     * A newer wire envelope / decrypt placeholder must not hide an older readable
     * conversation tail (group SK_DIST sometimes lands as TEXT ciphertext).
     */
    fun isUnreadableListHead(message: Message): Boolean {
        if (isListPreviewNoise(message.type)) return true
        return when (message.type) {
            MessageType.TEXT, MessageType.MARKDOWN ->
                looksLikeLeftoverPreviewGarbage(visiblePreviewText(message.content).ifBlank { message.content })
            else -> false
        }
    }

    /**
     * Heuristic for Signal JSON envelopes (object or multi-device array).
     * Media list labels like `[图片]` / `[GIF]` start with `[` but are not wire —
     * only treat as wire when the body looks like JSON structure.
     */
    /**
     * Chat-list TEXT preview must never dump `<meta>{...}</meta>` or a local file URI
     * after cache wipe. Room `lastMessage` historically stored composeContentWithMeta.
     */
    fun visiblePreviewText(raw: String): String {
        val withoutMeta = raw.lastIndexOf(com.maodouchat.data.model.Message.META_TAG_PREFIX).let { idx ->
            if (idx < 0) raw else raw.substring(0, idx)
        }.trim()
        if (withoutMeta.isBlank()) return ""
        if (looksLikeLocalMediaUri(withoutMeta)) return ""
        return com.maodouchat.util.ChatTextDisplayPolicy.unescapeHtmlEntities(withoutMeta)
    }

    /**
     * Chat-list TEXT row: strip meta, hide wire / leftover ciphertext dumps,
     * keep user-typed plaintext including ordinary https links.
     */
    fun listVisibleText(raw: String, encryptedPlaceholder: String): String {
        val body = visiblePreviewText(raw)
        if (body.isBlank() || looksLikeLeftoverPreviewGarbage(body)) return encryptedPlaceholder
        return body
    }

    /**
     * Failed decrypt / attachment meta sometimes lands as a bare CDN or third-party
     * join URL (e.g. leftover 云湖 share links). Do not hide a user-sent https link
     * that is ordinary plaintext.
     */
    fun looksLikeLeftoverPreviewGarbage(content: String): Boolean {
        val t = content.trim()
        if (t.isBlank()) return true
        if (looksLikeLocalMediaUri(t)) return true
        if (isSignalWireEnvelope(t)) return true
        if (t.startsWith("eyJ") && t.length >= 40) return true
        if (t.contains("<meta>", ignoreCase = true) || t.contains("</meta>", ignoreCase = true)) return true
        if (looksLikeDecryptFailurePlaceholder(t)) return true
        if (isUrlOnly(t) && looksLikeLeftoverCipherOrJoinUrl(t)) return true
        return false
    }

    fun looksLikeDecryptFailurePlaceholder(content: String): Boolean {
        return DecryptPlaceholderPolicy.isPlaceholder(content)
    }

    private fun isUrlOnly(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.contains(' ') || t.contains('\n')) return false
        return t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) ||
            t.startsWith("www.", ignoreCase = true)
    }

    private fun looksLikeLeftoverCipherOrJoinUrl(url: String): Boolean {
        val lower = url.lowercase()
        // User-typed https links (including 云湖 /share?id=) stay visible.
        // Only hide leftover attachment/cipher dumps, not ordinary join URLs.
        return lower.contains("/api/attachments") ||
            lower.contains("maodou-attachment") ||
            lower.contains("ciphertext") ||
            lower.contains("senderdeviceid")
    }

    fun looksLikeLocalMediaUri(content: String): Boolean {
        val t = content.trim()
        return t.startsWith("file:") ||
            t.startsWith("content:") ||
            t.startsWith("maodou-attachment://")
    }

    fun looksLikeWireEnvelope(content: String): Boolean {
        val t = content.trimStart()
        if (t.startsWith("{")) return true
        // Multi-device / JSON array envelopes start with `[` then `{` or `"`.
        // Localized labels are `[...]` without nested JSON.
        if (t.startsWith("[{") || t.startsWith("[\"")) return true
        return false
    }

    /**
     * 9.3xx：严格版 Signal 密文判定——详情气泡兜底。此前 FutureEpoch/断线补拉路径把
     * wire envelope 原样落库，气泡整块输出 ciphertext/设备号等元数据。
     * 仅当 JSON 结构同时命中 algorithm/ciphertext/distributionMessage 等 Signal 特征才判定，
     * 避免误伤用户正常发送的 `{...}` 开头的文本。
     */
    fun isSignalWireEnvelope(content: String): Boolean {
        val t = content.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[{")) return false
        if (t.contains("signal-")) {
            return t.contains("\"algorithm\"") || t.contains("\"distributionMessage\"") ||
                t.contains("\"ciphertext\"") || t.contains("\"senderKey")
        }
        // encodeDefaults=false omits version/algorithm; live 1:1 envelopes look like
        // {"senderDeviceId":N,"payloadType":"TEXT","entries":[{"ciphertext":...}]}
        val hasCiphertext = t.contains("\"ciphertext\"")
        val hasSenderDevice = t.contains("\"senderDeviceId\"")
        val hasEntries = t.contains("\"entries\"")
        val hasCiphertextType = t.contains("\"ciphertextType\"")
        val hasDistribution = t.contains("\"distributionMessage\"")
        return hasDistribution ||
            (hasCiphertext && (hasSenderDevice || hasEntries || hasCiphertextType))
    }

    /** UI / clipboard: never emit raw Signal JSON; keep Room wire for later decrypt. */
    fun redactedIfWire(content: String, placeholder: String): String {
        val body = visiblePreviewText(content)
        if (body.isBlank()) return placeholder
        return if (isSignalWireEnvelope(content) || isSignalWireEnvelope(body)) placeholder else body
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
        // This helper is called for a same-id local echo. A JSON object/array here may be a
        // wire envelope that has not been decrypted yet, even when an older/unknown envelope
        // shape does not satisfy the stricter Signal signature detector.
        if (looksLikeWireEnvelope(body)) return false
        if (looksLikeLeftoverPreviewGarbage(body)) return false
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
        val body = decryptedPlain?.takeIf {
            it.isNotBlank() &&
                !looksLikeWireEnvelope(it) &&
                !looksLikeLeftoverPreviewGarbage(it)
        }
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
