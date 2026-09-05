package com.maodouchat.domain.messaging

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 版本化消息内容信封（M01）。 */
@Serializable
data class ContentEnvelope(
    val version: Int = 1,
    val content: ContentPayload,
)

/**
 * 版本化 typed 消息内容协议（M01），替代旧正文 `<meta>...</meta>` 拼串。
 * wire 名用 @SerialName 固定；未知字段由 forwardCompatible Json 忽略；
 * 未知类型由 [decodeContentEnvelopeFailSafe] 降级为 [Unknown]（fail-safe，不抛异常）。
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

    /** 未来版本/未知类型的降级载体：保留原始 type 与完整 JSON，避免反序列化抛异常丢消息。 */
    @Serializable
    @SerialName("unknown")
    data class Unknown(
        val rawType: String,
        val rawPayload: String = "",
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

/**
 * M01 子项 4：fail-safe 反序列化——已知 type 正常解析；
 * 未知/未来 type（`@JsonClassDiscriminator` 无匹配子类会抛 SerializationException）降级为
 * [ContentPayload.Unknown]，保留原始 type 与 content JSON，不丢消息。
 */
fun decodeContentEnvelopeFailSafe(json: Json, raw: String): ContentEnvelope =
    runCatching { json.decodeFromString<ContentEnvelope>(raw) }.getOrElse {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        val content = obj?.get("content") as? JsonObject
        val rawType = content?.get("type")?.jsonPrimitive?.content ?: "unknown"
        ContentEnvelope(
            version = obj?.get("version")?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            content = ContentPayload.Unknown(rawType = rawType, rawPayload = content?.toString() ?: raw),
        )
    }
