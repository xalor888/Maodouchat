package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `send*Hint` 系列请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz`、`answerCallbackQuery`、
 * `sendChecklist`、`sendAlert`、`sendCountdown`、`sendNotice`、`sendBadge`、
 * `sendToast`、`sendHr`、`sendDivider`、`sendProgress` 之后**第二十八块**）。
 *
 * 十二个端点（`/api/bot/sendInviteHint`、`sendSafetyHint`、`sendQrHint`、
 * `sendSpoilerHint`、`sendDownloadHint`、`sendLocationHint`、`sendFileHint`、
 * `sendSecureHint`、`sendPhotoHint`、`sendVideoHint`、`sendGifHint`、
 * `sendWatermarkHint`）的处理器逐字同构，只有三处不同：各自的特性开关门控
 * （仍在处理器里，解析之前）、`hint` 的默认文案、内容模板的前缀
 * （`"INVITEHINT:"` / 各色 emoji + 空格）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把十二个处理器里
 * 内联的**抽取 / 校验**逻辑收敛为纯函数 [parseBotSendHintFields]，内容组装收敛为
 * [buildBotSendHintContent]，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `hint` 取 `(obj["hint"]?.jsonPrimitive?.content ?: defaultHint).take(120)`——
 *   默认文案只在**键缺席**时回退；显式 JSON null 得字面 `"null"`（`JsonNull`
 *   本身是 `JsonPrimitive`，`.content` 为 `"null"`，**不**回退默认值，特意钉住）；
 *   **不 trim**（原处理器逐字如此，前导空格计入 120 上限）；截断 120 逐字不变；
 * - 单必填（`chatId.isBlank()`→400 `"chatId required"`）；`hint` 非必填——
 *   缺省回默认文案，空白原样保留（组装时不判缺，特意钉住）；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 必填（纯函数）→ 成员检查（处理器）），
 *   各端点的特性开关门控仍在处理器解析之前；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 * - 消息内容组装逐字搬移：`prefix + hint`（`type = "SYSTEM"`，原处理器逐字如此，
 *   前缀与模板由各端点传入，不在此硬编码）。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotSendHintFields(
    val chatId: String,
    val hint: String,
)

internal sealed interface BotSendHintFieldsResult {
    data class Ok(val fields: BotSendHintFields) : BotSendHintFieldsResult
    data object MissingRequired : BotSendHintFieldsResult
}

/**
 * 十二个 `send*Hint` 端点共用的请求体解析。
 *
 * [defaultHint] 为各端点的默认提示文案（键缺席时回退）。抽取顺序与原处理器逐字一致：
 * 先 `chatId`、再 `hint`，最后判必填——坏类型字段的抛错顺序也因此不变。
 */
internal fun parseBotSendHintFields(obj: JsonObject, defaultHint: String): BotSendHintFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接存在性——hint 键缺席才回 defaultHint；显式 JSON null 得字面 "null"（非 null，不回退）；
    // 不 trim，前导空格计入 take(120) 上限（原处理器逐字顺序）。
    val hint = (obj["hint"]?.jsonPrimitive?.content ?: defaultHint).take(120)
    // 注意：单必填——hint 缺省/空白不判缺（原处理器逐字如此）。
    // 对象/数组型已知字段的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    if (chatId.isBlank()) {
        return BotSendHintFieldsResult.MissingRequired
    }
    return BotSendHintFieldsResult.Ok(BotSendHintFields(chatId, hint))
}

/**
 * 与原处理器逐字一致的内容组装：`prefix + hint`（`type = "SYSTEM"` 由处理器固定）。
 */
internal fun buildBotSendHintContent(prefix: String, hint: String): String = prefix + hint
