package com.maodouchat.server.service

import kotlinx.serialization.json.JsonPrimitive

internal fun AiGatewayService.estimateInputChars(input: List<OpenAiInputMessage>): Int {
    var chars = 0
    for (msg in input) {
        val content = msg.content
        if (content is JsonPrimitive && content.isString) chars += content.content.length
    }
    return chars
}

internal fun AiGatewayService.estimateInputTokens(input: List<OpenAiInputMessage>): Long =
    maxOf(1L, estimateInputChars(input) / 4L)
