package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.SpoilerMediaPolicy
import com.maodouchat.core.model.ViewOncePolicy

// geo: URI 解析出的经纬度；非法数字默认 0.0。
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
)

// 名片 URI 解析结果。
data class ContactCard(
    val userId: String,
    val displayName: String,
)

// 附件可见性归一结果：阅后即焚与剧透模糊是否实际生效。
data class AttachmentVisibility(
    val viewOnce: Boolean,
    val spoilerMedia: Boolean,
)

// 附件意图纯解析（纯逻辑）：贴纸/位置/名片 URI 解析、kind 映射与可见性归一。
object AttachmentIntentPolicy {

    // 贴纸码：去 sticker:// scheme、trim、截断 32 字；无有效码返回 null。
    fun parseStickerCode(uri: String): String? =
        uri.removePrefix("sticker://").trim().take(32).takeIf(String::isNotBlank)

    // geo:lat,lng 解析；经度去掉 ? 后的查询串，非法数字默认 0.0。
    fun parseGeoLocation(uri: String): GeoLocation {
        val parts = uri.removePrefix("geo:").split(",")
        return GeoLocation(
            latitude = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
            longitude = parts.getOrNull(1)?.substringBefore("?")?.toDoubleOrNull() ?: 0.0,
        )
    }

    // 名片 URI 解析：剥 scheme（:// 优先、否则 :）、? 前为 userId、name= 参数为显示名，
    // 无 name 参数时回落 caption、再回落 "contact"。
    fun parseContactCard(uri: String, caption: String?): ContactCard {
        val withoutScheme = uri.substringAfter("://", "").ifEmpty { uri.substringAfter(":", "") }
        val query = if (withoutScheme.contains("?")) withoutScheme.substringAfter("?") else ""
        val displayName = query.split("&")
            .firstOrNull { it.startsWith("name=") }
            ?.substringAfter("name=")
            ?.trim()
            ?.ifBlank { null }
            ?: caption.orEmpty().ifBlank { "contact" }
        return ContactCard(
            userId = withoutScheme.substringBefore("?").trim(),
            displayName = displayName,
        )
    }

    // 媒体 kind 映射为消息类型；非媒体 kind 归 FILE。
    fun messageTypeForKind(kind: AttachmentKind): MessageType = when (kind) {
        AttachmentKind.IMAGE -> MessageType.IMAGE
        AttachmentKind.VIDEO -> MessageType.VIDEO
        AttachmentKind.VOICE -> MessageType.VOICE
        AttachmentKind.FILE -> MessageType.FILE
        AttachmentKind.GIF -> MessageType.GIF
        else -> MessageType.FILE
    }

    // 可见性归一：阅后即焚仅支持的类型生效；剧透需未开阅后即焚且类型支持。
    fun resolveAttachmentVisibility(
        type: MessageType,
        viewOnce: Boolean,
        spoilerMedia: Boolean,
    ): AttachmentVisibility = AttachmentVisibility(
        viewOnce = viewOnce && ViewOncePolicy.supports(type),
        spoilerMedia = spoilerMedia && !viewOnce && SpoilerMediaPolicy.supports(type),
    )
}
