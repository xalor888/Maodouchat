package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendMessageParsed(
    val chatId: String,
    val text: String,
    val parseMode: String,
    val replyToId: String?,
    /**
     * 解析出但处理器当前未使用（静默发送走独立的 `sendMessageSilent` 端点）；
     * 保留字段以钉住解析语义，防止后人误删。
     */
    val silent: Boolean,
    val keyboardRows: List<List<Map<String, String>>>?,
    val forceReply: Boolean,
)

internal fun parseBotSendMessage(obj: JsonObject, silentSendEnabled: Boolean): BotSendMessageParsed {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val text = obj["text"]?.jsonPrimitive?.content.orEmpty().take(4000)
    val parseMode = obj["parseMode"]?.jsonPrimitive?.content.orEmpty().uppercase()
    val replyToId = obj["replyToMessageId"]?.jsonPrimitive?.content?.take(80)
    val silentRequested = obj["silent"]?.jsonPrimitive?.booleanOrNull == true
    val silent = silentRequested && silentSendEnabled
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
    return BotSendMessageParsed(
        chatId = chatId,
        text = text,
        parseMode = parseMode,
        replyToId = replyToId,
        silent = silent,
        keyboardRows = keyboardRows,
        forceReply = forceReplyFlag,
    )
}
