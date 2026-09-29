package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendPhoto` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、`sendVoice` 之后第五块）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotMediaRoutes` 的 `/api/bot/sendPhoto` 处理器里的**抽取 / 解码 / 内容组装**
 * 逻辑收敛为纯函数，行为与搬移前逐行一致——包括两处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `photoBase64`→`photo`→`fileBase64`→`data` 别名优先级、caption 500 截断
 *   （注意：sendPhoto 是 500，sendVoice 是 200——逐字保留，不统一）、
 *   空媒体一律拒绝（`chatId/photoBase64 required`，与 sendVoice/sendDocument 一致）；
 * - **base64 解码失败或解码出空字节都判 400 `invalid base64`**：原处理器用
 *   `runCatching { ... }.getOrNull()` + `bytes == null || bytes.isEmpty()`，
 *   与 sendVoice 的「坏 base64 只走 size=0 分支」宽容语义故意不同，这里逐字保留
 *   并用测试钉住（data-URI 前缀剥离与空白剔除同样逐字保留）；
 * - 体积上限 5MB（`photo too large (max 5MB)`，413），内容模板 + `.take(4000)` 原样保留。
 *
 * 校验顺序刻意与原处理器一致（必填 → 成员检查在处理器里 → 解码 → 体积），纯函数只负责
 * 抽取、解码与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里。
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendPhotoFields(
    val chatId: String,
    val caption: String,
    val fileBase64: String,
)

internal sealed interface BotSendPhotoFieldsResult {
    data class Ok(val fields: BotSendPhotoFields) : BotSendPhotoFieldsResult
    data object MissingRequired : BotSendPhotoFieldsResult
}

internal fun parseBotSendPhotoFields(obj: JsonObject): BotSendPhotoFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val caption = obj["caption"]?.jsonPrimitive?.content.orEmpty().take(500)
    val b64 = (obj["photoBase64"] ?: obj["photo"] ?: obj["fileBase64"] ?: obj["data"])
        ?.jsonPrimitive?.content.orEmpty()
    if (chatId.isBlank() || b64.isBlank()) return BotSendPhotoFieldsResult.MissingRequired
    return BotSendPhotoFieldsResult.Ok(BotSendPhotoFields(chatId, caption, b64))
}

/** 与原处理器逐字一致的图片体积上限（5MB）。 */
internal const val BOT_PHOTO_MAX_BYTES = 5 * 1024 * 1024

/**
 * 与原处理器逐字一致的图片解码（含 data-URI 前缀剥离与空白剔除）。
 *
 * 注意：解码失败（非法 base64）或解码出空字节都**返回 null**——原处理器用
 * `runCatching { decode(...) }.getOrNull()` 判 null，再用 `bytes.isEmpty()` 兜住空解码，
 * 两种情况都映射为 400 `invalid base64`（与 sendVoice 的「坏 base64 只走 size=0
 * 分支」宽容语义故意不同，零行为改动）。
 */
internal fun decodeBotPhotoBytes(b64: String): ByteArray? {
    val bytes = runCatching {
        java.util.Base64.getDecoder().decode(b64.substringAfter(',').replace("\\s".toRegex(), ""))
    }.getOrNull()
    return if (bytes == null || bytes.isEmpty()) null else bytes
}

/**
 * 与原处理器逐字一致的 IMAGE 消息内容组装（含 4000 截断）。
 * `byteSize` 取 [decodeBotPhotoBytes] 的解码结果长度（非空才走到这里）。
 */
internal fun buildBotPhotoContent(caption: String, byteSize: Int): String =
    buildString {
        append("🖼 photo ")
        append(byteSize)
        append("B")
        if (caption.isNotBlank()) {
            append("\n")
            append(caption)
        }
        append("\n[botPhotoSize:")
        append(byteSize)
        append("]")
    }.take(4000)
