package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 水印提取端点 "imageBase64" 抽取：缺席/坏 JSON/顶层非对象/非原始类型一律 → ""，
// 下游判空报 400 "imageBase64_required"。
internal fun parseAdminWatermarkImageBase64(body: String): String {
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["imageBase64"]?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault("")
}
