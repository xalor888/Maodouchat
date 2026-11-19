package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatListPreviewPolicy
import com.maodouchat.core.model.MessageType

/**
 * 列表/引用/复制/星标预览的 wire 信封净化：Signal 信封 JSON 只作密文占位，绝不外泄原文。
 * 由 :core:model 的 ChatListPreviewPolicy 迁入；严格判定与可见文本仍在 core，走 domain→core 调用。
 */
object WireEnvelopePreviewPolicy {

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

    /** UI / clipboard: never emit raw Signal JSON; keep Room wire for later decrypt. */
    fun redactedIfWire(content: String, placeholder: String): String {
        val body = ChatListPreviewPolicy.visiblePreviewText(content)
        if (body.isBlank()) return placeholder
        return if (ChatListPreviewPolicy.isSignalWireEnvelope(content) || ChatListPreviewPolicy.isSignalWireEnvelope(body)) placeholder else body
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
        if (ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage(body)) return false
        if (body == encryptedPlaceholder) return false
        return true
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
                !ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage(it)
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
