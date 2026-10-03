package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendLocationFields(
    val chatId: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
)

internal sealed interface BotSendLocationFieldsResult {
    data class Ok(val fields: BotSendLocationFields) : BotSendLocationFieldsResult
    data object MissingRequired : BotSendLocationFieldsResult
    data object InvalidCoordinates : BotSendLocationFieldsResult
}

internal fun parseBotSendLocationFields(obj: JsonObject): BotSendLocationFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接在 toDoubleOrNull() 之后——主字段非数字时穿透到别名（原处理器逐字语义）。
    val lat = obj["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: obj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull()
    val lon = obj["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: obj["lng"]?.jsonPrimitive?.content?.toDoubleOrNull()
        ?: obj["lon"]?.jsonPrimitive?.content?.toDoubleOrNull()
    val title = obj["title"]?.jsonPrimitive?.content?.take(80).orEmpty()
    if (chatId.isBlank() || lat == null || lon == null) return BotSendLocationFieldsResult.MissingRequired
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return BotSendLocationFieldsResult.InvalidCoordinates
    return BotSendLocationFieldsResult.Ok(BotSendLocationFields(chatId, lat, lon, title))
}

/**
 * 与原处理器逐字一致的 LOCATION 消息内容组装。
 * Bot plaintext location marker（clients may render map if they parse LOCATION body）。
 */
internal fun buildBotLocationContent(latitude: Double, longitude: Double, title: String): String =
    buildString {
        append("\uD83D\uDCCD ")
        if (title.isNotBlank()) {
            append(title)
            append(" ")
        }
        append(String.format(java.util.Locale.US, "%.6f,%.6f", latitude, longitude))
        append("\n[location:")
        append(latitude)
        append(",")
        append(longitude)
        append("]")
    }
