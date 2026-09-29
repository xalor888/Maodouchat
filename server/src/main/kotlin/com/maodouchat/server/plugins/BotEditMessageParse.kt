package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bot `editMessage` 请求体解析（清单 Q01「协议模型向前/向后兼容与 fuzz 测试」的 bot 侧专项评估，
 * G355 `sendMessage` 之后第二块）。
 *
 * Bot 路由是手写 `JsonObject` 解析、没有 typed DTO。本文件把原来内联在
 * `configureBotCoreRoutes` 的 `/api/bot/editMessage` 处理器里的抽取逻辑收敛为纯函数，
 * 行为与搬移前逐行一致——包括**已知字段类型错时抛 [IllegalArgumentException]**
 * （路由层 `StatusPages` 把它映射为 400「参数无效」，不是 500）。评估结论沿用 G355：
 * bot 侧手写解析本来就满足 fuzz 系列的契约（未知键忽略、缺省回默认值、坏类型大声失败、
 * 无未处理 500），这里只是把它变成可被测试钉住的形态，零行为改动。
 *
 * 注意 `messageId`/`text` 的非空校验仍留在处理器里（400 `messageId/text required`），
 * 本函数只做抽取与截断。
 */
internal data class BotEditMessageParsed(
    val messageId: String,
    val text: String,
    val keyboardRows: List<List<Map<String, String>>>?,
    val forceReply: Boolean,
)

internal fun parseBotEditMessage(obj: JsonObject): BotEditMessageParsed {
    val messageId = obj["messageId"]?.jsonPrimitive?.content.orEmpty()
    val text = obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000)
    val replyMarkup = obj["replyMarkup"]?.jsonObject ?: obj["reply_markup"]?.jsonObject
    val inlineKeyboardEl = replyMarkup?.get("inlineKeyboard")
        ?: replyMarkup?.get("inline_keyboard")
    val keyboardRows = (inlineKeyboardEl as? JsonArray)?.mapNotNull { rowEl ->
        val row = rowEl as? JsonArray ?: return@mapNotNull null
        row.mapNotNull { btnEl ->
            val b = btnEl as? JsonObject ?: return@mapNotNull null
            val t = b["text"]?.jsonPrimitive?.content.orEmpty().take(64)
            val d = (b["callbackData"] ?: b["callback_data"])?.jsonPrimitive?.content.orEmpty().take(128)
            if (t.isBlank()) null else mapOf("text" to t, "callbackData" to d)
        }.takeIf { it.isNotEmpty() }
    }?.filter { !it.isNullOrEmpty() }?.take(8)
    val forceReplyFlag = run {
        val fr = replyMarkup?.get("forceReply") ?: replyMarkup?.get("force_reply")
        when (fr) {
            is JsonPrimitive -> fr.booleanOrNull == true || fr.content.equals("true", true)
            is JsonObject -> true
            else -> false
        }
    }
    return BotEditMessageParsed(
        messageId = messageId,
        text = text,
        keyboardRows = keyboardRows,
        forceReply = forceReplyFlag,
    )
}
