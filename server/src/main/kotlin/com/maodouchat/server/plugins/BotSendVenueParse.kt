package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendVenueFields(
    val chatId: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val address: String,
)

internal sealed interface BotSendVenueFieldsResult {
    data class Ok(val fields: BotSendVenueFields) : BotSendVenueFieldsResult
    data object MissingRequired : BotSendVenueFieldsResult
    data object InvalidCoordinates : BotSendVenueFieldsResult
}

internal fun parseBotSendVenueFields(obj: JsonObject): BotSendVenueFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接在 toDoubleOrNull() 之后——主字段非数字时穿透到别名（原处理器逐字语义）。
    val lat = obj["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: obj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
    val lon = obj["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: obj["lng"]?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: obj["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
    // 注意：title 有 trim()（sendLocation 的没有），原处理器逐字如此。
    val title = obj["title"]?.jsonPrimitive?.content.orEmpty().trim().take(80)
    val address = obj["address"]?.jsonPrimitive?.content.orEmpty().trim().take(160)
    if (chatId.isBlank() || lat == null || lon == null || title.isBlank()) {
        return BotSendVenueFieldsResult.MissingRequired
    }
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
        return BotSendVenueFieldsResult.InvalidCoordinates
    }
    return BotSendVenueFieldsResult.Ok(BotSendVenueFields(chatId, lat, lon, title, address))
}

/**
 * 与原处理器逐字一致的 VENUE 消息内容组装。
 * 标记行的 lat/lon 是 Double 原始 toString（不是 %.6f）；title 里的竖线转斜线。
 */
internal fun buildBotVenueContent(latitude: Double, longitude: Double, title: String, address: String): String =
    buildString {
        append("\uD83D\uDCCC ")
        append(title)
        if (address.isNotBlank()) {
            append("\n")
            append(address)
        }
        append("\n")
        append(String.format(java.util.Locale.US, "%.6f,%.6f", latitude, longitude))
        append("\n[venue:")
        append(latitude)
        append(",")
        append(longitude)
        append("|")
        append(title.replace("|", "/"))
        append("]")
    }
