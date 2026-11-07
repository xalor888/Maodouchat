package com.maodouchat.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 解析推送 HMAC 密钥 JSON：`{"key":...}`；key 缺失/空 → 清理本地缓存。 */
sealed interface PushVerifyKeyAction {
    data object Clear : PushVerifyKeyAction
    data class Set(val key: String) : PushVerifyKeyAction
    data object Ignore : PushVerifyKeyAction
}

private val pushVerifyKeyJson = Json { ignoreUnknownKeys = true }

fun parsePushVerifyKeyPayload(raw: String): PushVerifyKeyAction {
    if (raw.isBlank()) return PushVerifyKeyAction.Ignore
    return runCatching {
        val obj = pushVerifyKeyJson.parseToJsonElement(raw).jsonObject
        val el = obj["key"]
        if (el == null || el is JsonNull) {
            // org.json 的 isNull：缺 key 与显式 null 都为真 -> 清理
            PushVerifyKeyAction.Clear
        } else if (el is JsonPrimitive) {
            // org.json 的 optString：字符串原值，数字/布尔取字面文本，对象/数组取空串
            val key = el.content
            if (key.isNotBlank() && key != "null") PushVerifyKeyAction.Set(key)
            else PushVerifyKeyAction.Ignore
        } else {
            PushVerifyKeyAction.Ignore
        }
    }.getOrDefault(PushVerifyKeyAction.Ignore)
}
