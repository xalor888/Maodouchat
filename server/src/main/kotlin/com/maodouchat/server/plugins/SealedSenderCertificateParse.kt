package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 密封发送方证书验证端点 "certificate" 抽取：坏 JSON、顶层非对象、
// certificate 非字符串都吞成 ""，下游 verify 会判失败并报 ok=false。
internal fun parseSealedSenderCertificate(body: String): String {
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["certificate"]?.jsonPrimitive?.content
    }.getOrNull().orEmpty()
}
