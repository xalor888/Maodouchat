package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `sendMentionCard` / `sendNudgeCard` 请求体解析（清单 Q01「协议模型向前/向后
 * 兼容与 fuzz 测试」的 bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、
 * `sendDocument`、`sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、
 * `sendSticker`、`sendContact`、`sendVenue`、`sendPoll`、`sendDice`、
 * `sendDiceCustom`、`forwardMessage`/`copyMessage`、`sendNudge`、`sendContactCard`、
 * `sendPollQuiz`、`answerCallbackQuery`、`sendChecklist`、`sendAlert`、
 * `sendCountdown`、`sendNotice`、`sendBadge`、`sendToast`、`sendHr`、`sendDivider`、
 * `sendProgress`、`send*Hint` 之后**第二十九块**）。
 *
 * 两个端点（`/api/bot/sendMentionCard`、`/api/bot/sendNudgeCard`）的处理器逐字同构，
 * 只有四处不同：各自的特性开关门控（仍在处理器里，解析之前——`sendMentionCard` 还多一道
 * `isMentionsEnabled` 门，`sendNudgeCard` 多一道 `isNudgeEnabled` 门）、`label` 的默认文案
 * （`"mention"` / `"nudge"`）、内容模板的包裹方式（`"> @" + label` / `"> ~nudge:" + label + "~"`）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把两个处理器里内联的
 * **抽取 / 校验**逻辑收敛为纯函数 [parseBotMentionNudgeFields]，内容组装收敛为
 * [buildBotMentionNudgeContent]，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - **已知字段类型错时抛 [IllegalArgumentException]**
 *   （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）；
 * - `label` 取 `(obj["label"]?.jsonPrimitive?.content ?: obj["text"]?.jsonPrimitive?.content
 *   ?: defaultLabel).take(80)`——回退链是**存在性**语义：显式 JSON null 得字面 `"null"`
 *   （`JsonNull` 本身是 `JsonPrimitive`，`.content` 为 `"null"`），**不**继续回退到
 *   `"text"` 或默认文案，特意钉住；**不 trim**，截断 80 逐字不变；
 * - 单必填（`chatId.isBlank()`→400 `"chatId required"`）；`label` 非必填（缺省回退链，
 *   空白原样保留不判缺）；
 * - 校验顺序刻意与原处理器一致（抽取（纯函数）→ 必填（纯函数）→ 成员检查（处理器）），
 *   各端点的特性开关门控仍在处理器解析之前；
 *   纯函数只负责抽取、校验与组装，不碰仓库 / 限流 / 响应——那些副作用仍留在处理器里；
 * - 消息内容组装逐字搬移：`prefix + label + suffix`（`type = "MARKDOWN"` 由处理器固定），
 *   包裹前后缀由各端点传入，不在此硬编码。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotMentionNudgeFields(
    val chatId: String,
    val label: String,
)

/**
 * 两个 mention/nudge 卡片端点共用的请求体解析。
 *
 * [defaultLabel] 为各端点的默认文案（`label` 键与 `text` 键都缺席时才回退；显式 JSON
 * null 得字面 `"null"`，不继续回退）。抽取顺序与原处理器逐字一致：先 `chatId`、
 * 再 `label`（经 `text` 回退链），最后判必填——坏类型字段的抛错顺序也因此不变。
 */
internal sealed interface BotMentionNudgeFieldsResult {
    data class Ok(val fields: BotMentionNudgeFields) : BotMentionNudgeFieldsResult
    data object MissingRequired : BotMentionNudgeFieldsResult
}

internal fun parseBotMentionNudgeFields(
    obj: JsonObject,
    defaultLabel: String,
): BotMentionNudgeFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：回退链接存在性——显式 JSON null 得字面 "null"（非 null，不继续回退）；
    // 不 trim，前导空格计入 take(80) 上限（原处理器逐字顺序）。
    val label = (obj["label"]?.jsonPrimitive?.content ?: obj["text"]?.jsonPrimitive?.content ?: defaultLabel).take(80)
    // 注意：单必填——label 缺省/空白不判缺（原处理器逐字如此）。
    // 对象/数组型已知字段的 ?.jsonPrimitive 抛 IllegalArgumentException（大声失败）。
    if (chatId.isBlank()) {
        return BotMentionNudgeFieldsResult.MissingRequired
    }
    return BotMentionNudgeFieldsResult.Ok(BotMentionNudgeFields(chatId, label))
}

/**
 * 与原处理器逐字一致的内容组装：`prefix + label + suffix`
 * （`sendMentionCard`: `"> @"` + label；`sendNudgeCard`: `"> ~nudge:"` + label + `"~"`）。
 */
internal fun buildBotMentionNudgeContent(prefix: String, label: String, suffix: String): String =
    prefix + label + suffix
