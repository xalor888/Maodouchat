package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotSendContactFields(
    val chatId: String,
    val contactName: String,
    val phone: String,
    val userId: String,
)

internal sealed interface BotSendContactFieldsResult {
    data class Ok(val fields: BotSendContactFields) : BotSendContactFieldsResult
    data object MissingRequired : BotSendContactFieldsResult
}

internal fun parseBotSendContactFields(obj: JsonObject): BotSendContactFieldsResult {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    // 注意：?: 接在 jsonPrimitive 之前——主字段存在即不穿透别名（哪怕显式 null），原处理器逐字语义。
    val contactName = (obj["name"] ?: obj["firstName"])?.jsonPrimitive?.content.orEmpty().trim().take(80)
    val phone = (obj["phone"] ?: obj["phoneNumber"])?.jsonPrimitive?.content.orEmpty().trim().take(40)
    // 注意：userId 没有 trim()——与 name/phone 不对称，原处理器逐字如此。
    val userId = obj["userId"]?.jsonPrimitive?.content?.take(64).orEmpty()
    if (chatId.isBlank() || (contactName.isBlank() && userId.isBlank() && phone.isBlank())) {
        return BotSendContactFieldsResult.MissingRequired
    }
    return BotSendContactFieldsResult.Ok(BotSendContactFields(chatId, contactName, phone, userId))
}

/**
 * 与原处理器逐字一致的 CONTACT 消息内容组装。
 *
 * 首行 `"👤 "` 起手固定带尾空格：name 为空而 phone 非空时不补空格（`isNotEmpty() &&
 * !endsWith(" ")`），name 非空（已 trim，尾无空格）时补一个空格隔开 phone；
 * userId / phone 各自可选的 `\n[contactUser:]` / `\n[contactPhone:]` 标记行原样保留。
 */
internal fun buildBotContactContent(contactName: String, phone: String, userId: String): String =
    buildString {
        append("\uD83D\uDC64 ")
        if (contactName.isNotBlank()) append(contactName)
        if (phone.isNotBlank()) {
            if (isNotEmpty() && !endsWith(" ")) append(" ")
            append(phone)
        }
        if (userId.isNotBlank()) {
            append("\n[contactUser:")
            append(userId)
            append("]")
        }
        if (phone.isNotBlank()) {
            append("\n[contactPhone:")
            append(phone)
            append("]")
        }
    }
