package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendVoice` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage`、G355-2 `editMessage`、`sendDocument` 之后第四块）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotMediaRoutes` 的 `/api/bot/sendVoice` 处理器里的**抽取 / 体积测量 / 内容组装**
 * 逻辑收敛为纯函数，行为与搬移前逐行一致——包括两处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `duration` 的 `duration`→`durationSec` 与 `fileBase64`→`voice`→`data` 别名优先级、
 *   caption 200 截断、空媒体一律拒绝（`chatId/voice required`，与 sendPhoto/sendDocument 一致）；
 * - **base64 解码失败不报错**：原处理器用 `getOrDefault(0)`，坏 base64 的 voice 只走
 *   「size = 0」分支（内容模板里不拼体积），**不会**触发 400——与 sendDocument 的
 *   `InvalidBase64` 语义故意不同，这里逐字保留并用测试钉住；
 * - 体积上限 4MB（`voice too large (max 4MB)`，413），内容模板 + `.take(4000)` 原样保留。
 *
 * 校验顺序刻意与原处理器一致（必填 → 成员检查在处理器里 → 体积），纯函数只负责
 * 抽取、测量与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendVoiceFields(
    val chatId: String,
    val durationSec: Int,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendVoiceFieldsResult {
    data class Ok(val fields: BotSendVoiceFields) : BotSendVoiceFieldsResult
    data object MissingRequired : BotSendVoiceFieldsResult
}

internal fun parseBotSendVoiceFields(obj: JsonObject): BotSendVoiceFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val duration = (obj["duration"] ?: obj["durationSec"])?.jsonPrimitive?.content?.toIntOrNull() ?: 0
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(200)
    val b64 = (obj["fileBase64"] ?: obj["voice"] ?: obj["data"])?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendVoiceFieldsResult.MissingRequired
    return BotSendVoiceFieldsResult.Ok(BotSendVoiceFields(chatId, duration, caption, b64))
}

/** 与原处理器逐字一致的语音体积上限（4MB）。 */
internal const val BOT_VOICE_MAX_BYTES = 4 * 1024 * 1024

/**
 * 与原处理器逐字一致的语音体积累积（含 data-URI 前缀剥离与空白剔除）。
 *
 * 注意：解码失败（非法 base64）或空输入**返回 0 而不是报错**——原处理器用
 * `runCatching { ... }.getOrDefault(0)`，坏 base64 的 voice 只是内容里不拼体积段，
 * 不触发 400（与 sendDocument 的 `InvalidBase64` 语义故意不同，零行为改动）。
 */
internal fun measureBotVoiceSize(b64: String): Int =
    if (b64.isNotBlank()) {
        runCatching {
            java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), "")).size
        }.getOrDefault(0)
    } else 0

/**
 * 与原处理器逐字一致的 VOICE 消息内容组装（含 4000 截断）。
 * `byteSize` 取 [measureBotVoiceSize] 的返回值（0 时模板里不出现体积段）。
 */
internal fun buildBotVoiceContent(durationSec: Int, caption: String, byteSize: Int): String =
    buildString {
        append("🎤 voice")
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
        append("\n[botVoiceSize:")
        append(byteSize)
        append("]")
    }.take(4000)
