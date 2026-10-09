package com.maodouchat.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// OpenAI Responses 响应解析簇。

internal object LocalAiResponsesParse {

    fun parseResponses(payload: String): OpenAiCompatClient.Completion {
        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: return OpenAiCompatClient.Completion.Error("模型返回不是 JSON")
        val output = root.optJSONArray("output") ?: JSONArray()
        val calls = mutableListOf<AgentToolCall>()
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "function_call", "tool_call" -> {
                    val args = item.optString("arguments").ifBlank {
                        item.optJSONObject("function")?.optString("arguments").orEmpty()
                    }
                    val name = item.optString("name").ifBlank {
                        item.optJSONObject("function")?.optString("name").orEmpty()
                    }
                    if (name.isNotBlank()) {
                        calls += AgentToolCall(
                            id = item.optString("call_id").ifBlank { item.optString("id") }
                                .ifBlank { "call_${UUID.randomUUID()}" },
                            name = name,
                            argumentsJson = args.ifBlank { "{}" }
                        )
                    }
                }
                "message" -> {
                    val content = item.optJSONArray("content") ?: JSONArray()
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") in setOf("output_text", "text")) {
                            text.append(part.optString("text"))
                        }
                    }
                }
            }
        }
        if (text.isBlank()) text.append(root.optString("output_text"))
        if (calls.isNotEmpty()) return OpenAiCompatClient.Completion.Tools(calls, text.toString())
        return OpenAiCompatClient.Completion.Text(text.toString())
    }
}
