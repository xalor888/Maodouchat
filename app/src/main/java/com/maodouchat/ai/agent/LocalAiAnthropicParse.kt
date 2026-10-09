package com.maodouchat.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Anthropic Messages 响应解析簇。

internal object LocalAiAnthropicParse {

    fun parseAnthropic(payload: String): OpenAiCompatClient.Completion {
        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: return OpenAiCompatClient.Completion.Error("模型返回不是 JSON")
        val content = root.optJSONArray("content") ?: JSONArray()
        val calls = mutableListOf<AgentToolCall>()
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> {
                    val input = block.opt("input")
                    calls += AgentToolCall(
                        id = block.optString("id").ifBlank { "call_${UUID.randomUUID()}" },
                        name = block.optString("name"),
                        argumentsJson = when (input) {
                            null -> "{}"
                            is JSONObject -> input.toString()
                            else -> input.toString()
                        }
                    )
                }
            }
        }
        if (calls.isNotEmpty()) return OpenAiCompatClient.Completion.Tools(calls, text.toString())
        return OpenAiCompatClient.Completion.Text(text.toString())
    }
}
