package com.maodouchat.chatlist

/** 解析推送 HMAC 密钥 JSON：`{"key":...}`；key 缺失/空 → 清理本地缓存。 */
internal sealed interface PushVerifyKeyAction {
    data object Clear : PushVerifyKeyAction
    data class Set(val key: String) : PushVerifyKeyAction
    data object Ignore : PushVerifyKeyAction
}

internal fun parsePushVerifyKeyPayload(raw: String): PushVerifyKeyAction {
    if (raw.isBlank()) return PushVerifyKeyAction.Ignore
    return runCatching {
        val o = org.json.JSONObject(raw)
        if (o.isNull("key")) {
            PushVerifyKeyAction.Clear
        } else {
            val key = o.optString("key")
            if (key.isNotBlank() && key != "null") PushVerifyKeyAction.Set(key)
            else PushVerifyKeyAction.Ignore
        }
    }.getOrDefault(PushVerifyKeyAction.Ignore)
}
