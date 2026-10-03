package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
