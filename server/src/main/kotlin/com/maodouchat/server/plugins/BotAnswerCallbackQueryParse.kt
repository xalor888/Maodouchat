package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `answerCallbackQuery` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的
 * bot 侧专项评估，G355 `sendMessage`、G355-2 `editMessage`、`sendDocument`、
 * `sendVoice`、`sendPhoto`、`sendVideo`、`sendLocation`、`sendSticker`、`sendContact`、
 * `sendVenue`、`sendPoll`、`sendDice`、`sendDiceCustom`、`forwardMessage`/`copyMessage`、
 * `sendNudge`、`sendContactCard`、`sendPollQuiz` 之后**第十八块**）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotCallbackRoutes` 的 `/api/bot/answerCallbackQuery` 处理器里的**抽取**
 * 逻辑收敛为纯函数，行为与搬移前逐行一致——包括几处故意保留的「怪」语义：
 *
 * - `callbackQueryId` 有别名链 `callbackQueryId`→`id`（`?:` 接在字段**存在性**上：
 *   `callbackQueryId` 键存在但为显式 JSON null 时**不**穿透到 `id`，得字面 `"null"`）；
 *   两键都缺才回 `""`（缺省不 400——端点只做 ack，本来就没有必填校验）；
 * - `text` 取 `.orNull?.take(200)`：`.take(200)` **不 trim**（原处理器逐字如此），
 *   前导空格计入 200 上限；缺失时纯函数返回 `null`，处理器在组装响应时才回 `""`
 *   （`put("text", (text ?: ""))`——零行为改动）；
 * - 已知字段类型错（对象/数组型 `callbackQueryId`/`id`/`text`）时 `?.jsonPrimitive`
 *   抛 [IllegalArgumentException]（路由层 `StatusPages` 把它映射为 400「参数无效」，
 *   不是 500）；显式 JSON null 是 `JsonPrimitive` 子类型、`.content` 为字面
 *   `"null"`——**不是类型错**（原处理器逐字如此，特意钉住）。
 *
 * 评估结论沿用 G355：bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、
 * 缺省回默认值、坏类型大声失败、无未处理 500），这里只是把它变成可被测试钉住的形态，
 * 零行为改动。
 */
internal data class BotAnswerCallbackQueryFields(
    val callbackQueryId: String,
    val text: String?,
)

/**
 * 与原处理器逐行一致的 `answerCallbackQuery` 字段抽取（`callbackQueryId`→`id`
 * 别名链、`text` take(200) 不 trim、无必填校验）。
 */
internal fun parseBotAnswerCallbackQueryFields(obj: JsonObject): BotAnswerCallbackQueryFields {
    // 注意：?: 接在字段存在性上——callbackQueryId 键存在（哪怕是显式 JSON null）
    // 就不穿透到 id，得字面 "null"（原处理器逐字语义）；两键都缺才回 ""。
    val callbackQueryId = obj["callbackQueryId"]?.jsonPrimitive?.content
        ?: obj["id"]?.jsonPrimitive?.content
        ?: ""
    // 注意：take(200) 不 trim，前导空格计入上限（原处理器逐字语义）；缺省回 null
    //（不是 ""）——"" 的回退在处理器组装响应体时发生。
    val text = obj["text"]?.jsonPrimitive?.content?.take(200)
    return BotAnswerCallbackQueryFields(callbackQueryId, text)
}
