package com.maodouchat.domain.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** 版本化消息内容信封（M01）。 */
@Serializable
data class ContentEnvelope(
    val version: Int = 1,
    val content: ContentPayload,
)

/**
 * 版本化 typed 消息内容协议（M01），替代旧正文 `<meta>...</meta>` 拼串。
 * wire 名用 @SerialName 固定，未知字段由 forwardCompatible Json 忽略，未来版本可降级。
 */
@Serializable
@JsonClassDiscriminator("type")
sealed interface ContentPayload {

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        val mentions: List<Mention> = emptyList(),
    ) : ContentPayload

    @Serializable
    @SerialName("reply")
    data class Reply(
        val replyToMessageId: String,
        val content: ContentPayload,
    ) : ContentPayload

    @Serializable
    @SerialName("attachment")
    data class Attachment(
        val kind: AttachmentKind,
        val attachmentId: String,
        val fileName: String? = null,
        val mimeType: String? = null,
        val sizeBytes: Long? = null,
        val durationMs: Long? = null,
    ) : ContentPayload

    @Serializable
    @SerialName("location")
    data class Location(
        val latitude: Double,
        val longitude: Double,
    ) : ContentPayload

    @Serializable
    @SerialName("contact")
    data class Contact(
        val userId: String,
        val displayName: String,
    ) : ContentPayload

    @Serializable
    @SerialName("poll")
    data class Poll(
        val pollId: String,
        val question: String,
        val options: List<String>,
    ) : ContentPayload

    @Serializable
    @SerialName("system")
    data class SystemEvent(
        val eventType: String,
        val data: String? = null,
    ) : ContentPayload
}

@Serializable
enum class AttachmentKind {
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("voice") VOICE,
    @SerialName("file") FILE,
    @SerialName("sticker") STICKER,
    @SerialName("gif") GIF,
}

@Serializable
data class Mention(
    val userId: String,
    val displayName: String,
)
