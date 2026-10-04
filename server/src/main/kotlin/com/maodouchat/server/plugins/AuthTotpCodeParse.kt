package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// TOTP 三端点（recover-codes / confirm / disable）请求体 "code" 抽取：
// 坏 JSON、顶层非对象、code 非字符串都吞成 ""，下游 MfaService 会验不过并报 400。
internal fun parseAuthTotpCode(body: String): String {
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
    }.getOrNull().orEmpty()
}
