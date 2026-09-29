package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendVideo` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice`、`sendPhoto` 之后第六块）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotMediaRoutes` 的 `/api/bot/sendVideo` 处理器里的**抽取 / 体积测量 / 内容组装**
 * 逻辑收敛为纯函数，行为与搬移前逐行一致——包括两处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 *   但注意**显式 JSON null 不是类型错**：`JsonNull` 本就是 `JsonPrimitive` 的子类型，
 *   `duration` 显式 null 时 `.content` 为字符串 `"null"`，走 `toIntOrNull() ?: 0` 回 0——
 *   与原内联处理器逐字一致，测试特意钉住；
 * - `videoBase64`→`fileBase64`→`data` 与 `duration`→`durationSec` 别名优先级、
 *   caption 500 截断、空媒体一律拒绝（`chatId/videoBase64 required`，9.138 语义
 *   与 sendPhoto/sendDocument 一致）；
 * - **base64 解码失败不报错**：原处理器用 `getOrDefault(0)`，坏 base64 的 video 只走
 *   「size = 0」分支（内容模板里不拼体积段），**不会**触发 400——与 sendPhoto 的
 *   `invalid base64` 严格语义故意不同、与 sendVoice 的宽容语义一致，这里逐字保留
 *   并用测试钉住（data-URI 前缀剥离与空白剔除同样逐字保留）；
 * - 体积上限 12MB（`video too large (max 12MB)`，413），内容模板 + `.take(4000)` 原样保留。
 *
 * 校验顺序刻意与原处理器一致（必填 → 成员检查在处理器里 → 体积），纯函数只负责
 * 抽取、测量与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendVideoFields(
    val chatId: String,
    val durationSec: Int,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendVideoFieldsResult {
    data class Ok(val fields: BotSendVideoFields) : BotSendVideoFieldsResult
    data object MissingRequired : BotSendVideoFieldsResult
}

internal fun parseBotSendVideoFields(obj: JsonObject): BotSendVideoFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
    val duration = (obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val b64 = (obj["videoBase64"] ?: obj["fileBase64"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendVideoFieldsResult.MissingRequired
    return BotSendVideoFieldsResult.Ok(BotSendVideoFields(chatId, duration, caption, b64))
}

/** 与原处理器逐字一致的视频体积上限（12MB）。 */
internal const val BOT_VIDEO_MAX_BYTES = 12 * 1024 * 1024

/**
 * 与原处理器逐字一致的视频体积累积（含 data-URI 前缀剥离与空白剔除）。
 *
 * 注意：解码失败（非法 base64）或空输入**返回 0 而不是报错**——原处理器用
 * `runCatching { ... }.getOrDefault(0)`，坏 base64 的 video 只是内容里不拼体积段，
 * 不触发 400（与 sendPhoto 的 `invalid base64` 严格语义故意不同、
 * 与 sendVoice 的宽容语义一致，零行为改动）。
 */
internal fun measureBotVideoSize(b64: String): Int =
    if (b64.isNotBlank()) {
        runCatching {
            java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), "")).size
        }.getOrDefault(0)
    } else 0

/**
 * 与原处理器逐字一致的 VIDEO 消息内容组装（含 4000 截断）。
 * `byteSize` 取 [measureBotVideoSize] 的返回值（0 时模板里不出现体积段），
 * `durationSec` 非正时模板里不出现时长段。
 */
internal fun buildBotVideoContent(durationSec: Int, caption: String, byteSize: Int): String =
    buildString {
        append("🎬 video")
        if (durationSec > 0) {
            append(" ")
            append(durationSec)
            append("s")
        }
        if (byteSize > 0) {
            append(" (")
            append(byteSize)
            append("B)")
        }
        if (caption.isNotBlank()) {
            append("\n")
            append(caption)
        }
        append("\n[botVideoSize:")
        append(byteSize)
        append("]")
    }.take(4000)
