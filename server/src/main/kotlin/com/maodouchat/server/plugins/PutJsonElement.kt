package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder

// buildJsonObject 里嵌 typed 值：旧写法先 encodeToString 再 parseToJsonElement，
// 是多余的一次序列化往返；直接 encodeToJsonElement，结果相等。
internal inline fun <reified T> JsonObjectBuilder.putJsonElement(key: String, value: T) {
    put(key, Json.encodeToJsonElement(value))
}
