package com.maodouchat.core.model

import kotlinx.serialization.Serializable

enum class MessageType {
    TEXT, MARKDOWN, IMAGE, GIF, STICKER, LOCATION, VOICE, VIDEO, FILE, NUDGE, SK_DIST, SYSTEM, REVOKED;

    val isHidden: Boolean get() = this == SK_DIST

    companion object {
        fun fromWire(value: String?): MessageType = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TEXT
    }
}

@Serializable
data class InlineKeyboardButton(
    val text: String = "",
    val callbackData: String = ""
)

/** 消息附带的元数据；与正文一起进入 Signal/Sender Key 密文，并存入本机加密数据库。 */
@Serializable
data class MessageMeta(
    val mentions: List<String> = emptyList(),   // 被 @ 的 userId 列表（不含 @ 本身）
    val replyToId: String? = null,              // 被回复的消息 id
    /** 0.67 新功能：转发来源显示名（TG 式「已转发」标记；密聊转发不记录来源，仅置标记）。 */
    val forwardedFrom: String? = null,
    val voiceTranscript: String? = null,        // 语音转文字结果（本地缓存）
    val voiceDurationMs: Long? = null,
    val translations: Map<String, String> = emptyMap(), // 消息翻译结果，key 为目标语言
    val preferredTranslationLanguage: String? = null,
    val aiImageAnalyses: Map<String, String> = emptyMap(), // 图片 AI 结果，key 为 mode
    val preferredImageAnalysisMode: String? = null,
    val aiFileAnalyses: Map<String, String> = emptyMap(),  // 文件 AI 结果，key 为 mode 或 question
    val preferredFileAnalysisMode: String? = null,
    val aiFileLastQuestion: String? = null,
    val aiAssisted: Boolean = false,
    val markdown: Boolean = false,
    val aiAssistantMode: String? = null,
    val fileName: String? = null,
    val fileMimeType: String? = null,
    val fileSizeBytes: Long? = null,
    val attachmentId: String? = null,
    val attachmentKeyBase64: String? = null,
    val attachmentIvBase64: String? = null,
    val attachmentCipherSha256: String? = null,
    val attachmentPlainSha256: String? = null,
    val attachmentCipherSize: Long? = null,
    /** Telegram-style view-once media; after first open, local media is wiped. */
    val viewOnce: Boolean = false,
    /** Local-only: receiver already opened view-once media. */
    val viewOnceOpened: Boolean = false,
    /** Telegram-style silent send (no push / muted notify). Travels in E2EE meta. */
    val silent: Boolean = false,
    /** Blur media until viewer taps (Telegram spoiler media). */
    val spoilerMedia: Boolean = false,
    /** Local-only: viewer revealed spoiler media. */
    val spoilerRevealed: Boolean = false,
    /** Bot / system inline keyboard (Telegram-style). Rows of buttons. */
    val inlineKeyboard: List<List<InlineKeyboardButton>> = emptyList(),
    /** Bot force-reply: client should focus composer as reply to this message. */
    val forceReply: Boolean = false
)

@Serializable
data class MessageReaction(
    val userId: String,
    val emoji: String,
    val reactedAt: Long = System.currentTimeMillis()
)

@Serializable
data class LocationPayload(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val label: String = "",
    val capturedAt: Long = System.currentTimeMillis(),
    /** Telegram-style live location session (client-driven updates). */
    val live: Boolean = false,
    val liveUntil: Long? = null,
    val sessionId: String? = null
)
