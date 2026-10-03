package com.maodouchat.server.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun parseAddBotToChatBotId(body: String): String {
    // 注意：runCatching 覆盖 parseToJsonElement + jsonObject + 抽取整个链，
    // 任意环节抛异常（坏 JSON、顶层非对象、botId 对象/数组型）都得 ""（吞掉），
    // 处理器再把空白报成 400 "botId required"——逐字怪语义。
    return runCatching {
        Json.parseToJsonElement(body).jsonObject["botId"]?.jsonPrimitive?.content
    }.getOrNull().orEmpty()
}
