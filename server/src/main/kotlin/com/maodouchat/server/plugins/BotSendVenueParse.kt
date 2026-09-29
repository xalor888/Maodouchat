package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendVenue` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto`、
 * `sendVideo`、`sendLocation`、`sendSticker`、`sendContact` 之后**第十块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotGeoRoutes` 的 `/api/bot/sendVenue` 处理器里的**抽取 / 校验 /
 * 内容组装**逻辑收敛为纯函数，行为与搬移前逐行一致——包括两处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - 别名链的「穿透」语义：`latitude`→`lat`、`longitude`→`lng`→`lon` 是用
 *   `?:` 链在 **`toDoubleOrNull()` 之后**接的——主字段存在但非数字（甚至显式
 *   JSON null：`JsonNull` 本就是 `JsonPrimitive`，`.content` 为 `"null"` 字符串）
 *   时**穿透到别名**继续找，而不是直接判缺；只有整条链都取不到数字才
 *   `MissingRequired`（`chatId/latitude/longitude/title required`，400）。`sendLocation`
 *   第七块已是同一族语义，这里逐字保留并用测试钉住；
 * - title / address 的 `.orEmpty().trim().take(80/160)` 不对称（sendLocation 的 title
 *   是 `.content?.take(80).orEmpty()` 无 `trim()`），原处理器逐字如此，钉住防「顺手修」；
 * - 内容模板原样保留：`"📌 "` + title + 可选 `"\n"` + address + `"\n"` + `%.6f` 坐标 +
 *   `"\n[venue:"` + `lat` + `","` + `lon` + `"|"` + `title.replace("|", "/")` + `"]"`——
 *   注意标记行里 lat/lon 是 Double 原始 `toString()`（不是 `%.6f`），逐字保留。
 *
 * 校验顺序刻意与原处理器一致（必填 → 坐标范围（纯函数）→ 成员检查（处理器）），纯函数只负责
 * 抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
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
