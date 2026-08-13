package com.maodouchat.data.model

import kotlinx.serialization.Serializable

enum class MessageType {
    TEXT, MARKDOWN, IMAGE, GIF, STICKER, LOCATION, VOICE, VIDEO, FILE, NUDGE, SK_DIST, SYSTEM, REVOKED;

    val isHidden: Boolean get() = this == SK_DIST

    companion object {
        fun fromWire(value: String?): MessageType = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TEXT
    }
}

enum class MessageStatus {
    SENDING, SENT, DELIVERED, READ, FAILED;

    /** Delivery ladder rank; FAILED is a terminal local side-channel and never upgrades/downgrades the ladder. */
    fun deliveryRank(): Int = when (this) {
        SENDING -> 0
        SENT -> 1
        DELIVERED -> 2
        READ -> 3
        FAILED -> -1
    }

    /** True when [next] is a valid advance (or same) on the delivery ladder. FAILED only replaces SENDING. */
    fun canAdvanceTo(next: MessageStatus): Boolean {
        if (this == next) return true
        if (next == FAILED) return this == SENDING
        // 8.45：FAILED 允许被服务端权威帧（SENT/DELIVERED/READ）覆盖——失败只是本地
        // side-channel；若消息实际已送达（服务端状态流可能跳过 SENT 直接发 DELIVERED），
        // 应恢复为真实送达状态而非永远显示失败。
        if (this == FAILED) return next == SENDING || next == SENT || next == DELIVERED || next == READ
        return next.deliveryRank() > deliveryRank()
    }

    companion object {
        /**
         * Unknown/missing wire values default conservatively to DELIVERED for inbound payloads
         * rather than inventing SENT, which can clobber local READ state after merge.
         */
        fun fromWire(value: String?): MessageStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DELIVERED
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

@Serializable
data class Message(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val editedAt: Long? = null,
    val starred: Boolean = false,
    val meta: MessageMeta = MessageMeta(),
    val reactions: List<MessageReaction> = emptyList(),
    val expiresAt: Long? = null,
    val sealedSender: Boolean = false
) {
    /** 解包 content 里的 <meta>{...}</meta> 标签。
     * 9.143：用 lastIndexOf——正文可能含字面 "<meta>"（用户输入），真实 meta 块恒由
     * composeContentWithMeta 追加在末尾；此前 substringBefore 取首个出现位置，
     * 含该字面量的消息会被截断显示（正文后段丢失）。 */
    fun parsedContent(): String = content.lastIndexOf(META_TAG_PREFIX).let { idx ->
        if (idx < 0) content else content.substring(0, idx)
    }

    fun parsedMeta(): MessageMeta {
        val idx = content.lastIndexOf(META_TAG_PREFIX)
        if (idx < 0) return meta
        val json = content.substring(idx + META_TAG_PREFIX.length).substringBefore("</meta>")
        return runCatching {
            com.maodouchat.util.JsonFormat.fromJsonString(json)
        }.getOrDefault(meta)
    }

    fun withEncodedMeta(updatedMeta: MessageMeta): Message {
        val encoded = com.maodouchat.util.JsonFormat.encodeMessageMeta(updatedMeta)
        return copy(
            content = parsedContent() + META_TAG_PREFIX + encoded + "</meta>",
            meta = updatedMeta
        )
    }

    fun parsedLocation(): LocationPayload? {
        if (type != MessageType.LOCATION) return null
        return runCatching {
            LOCATION_JSON.decodeFromString(LocationPayload.serializer(), parsedContent())
        }.getOrNull()?.takeIf {
            it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
        }
    }

    companion object {
        const val META_TAG_PREFIX = "<meta>"
        private val LOCATION_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
