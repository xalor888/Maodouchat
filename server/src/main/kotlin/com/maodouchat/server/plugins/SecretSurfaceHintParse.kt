package com.maodouchat.server.plugins

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class SecretSurfaceHintFields(val chatId: String, val hint: String)

// 9.136：hint 走 sanitizeBotHint（控制字符/换行不得进入 SYSTEM 消息），空白回退默认文案；
// chatId 为空仍由路由报 400 "chatId required"。
internal fun parseSecretSurfaceHint(obj: JsonObject, defaultHint: String): SecretSurfaceHintFields {
    val chatId = obj["chatId"]?.jsonPrimitive?.content.orEmpty()
    val hint = sanitizeBotHint(obj["hint"]?.jsonPrimitive?.content).ifBlank { defaultHint }
    return SecretSurfaceHintFields(chatId, hint)
}
