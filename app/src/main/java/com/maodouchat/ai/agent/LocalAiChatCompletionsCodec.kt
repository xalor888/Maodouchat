package com.maodouchat.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Chat Completions 协议簇：请求编码 + 响应解析。

internal object LocalAiChatCompletionsCodec {

private fun applySampling(body: JSONObject, provider: LocalAiProvider) {
    provider.temperature?.let { body.put("temperature", it.coerceIn(0.0, 2.0)) }
    provider.topP?.let { body.put("top_p", it.coerceIn(0.0, 1.0)) }
    body.put("max_tokens", provider.clampedMaxTokens())
}

fun chatCompletionsBody(
    provider: LocalAiProvider,
    messages: List<AgentChatMessage>,
    tools: List<Map<String, Any?>>?,
    stream: Boolean
): JSONObject {
    val body = JSONObject()
        .put("model", provider.model)
        .put("stream", stream && tools.isNullOrEmpty())
        .put("messages", encodeChatMessages(messages))
    applySampling(body, provider)
    if (!tools.isNullOrEmpty()) {
        body.put("tools", JSONArray(tools.map { LocalAiProtocolCodec.toJson(it) }))
        body.put("tool_choice", "auto")
        body.put("stream", false)
    }
    return body
}

fun parseChatCompletions(payload: String): OpenAiCompatClient.Completion {
    val root = runCatching { JSONObject(payload) }.getOrNull()
        ?: return OpenAiCompatClient.Completion.Error("模型返回不是 JSON")
    val choice = root.optJSONArray("choices")?.optJSONObject(0)
        ?: return OpenAiCompatClient.Completion.Error("模型没有 choices")
    val message = choice.optJSONObject("message") ?: JSONObject()
    return parseOpenAiMessage(message)
}

private fun parseOpenAiMessage(message: JSONObject): OpenAiCompatClient.Completion {
    val toolCalls = message.optJSONArray("tool_calls")
    val contentRaw = message.opt("content")
    val content = if (contentRaw is String && contentRaw.isNotEmpty()) {
        contentRaw
    } else {
        val reasoning = message.opt("reasoning_content")
        if (reasoning is String) reasoning else message.optString("content")
    }
    if (toolCalls != null && toolCalls.length() > 0) {
        val calls = buildList {
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                add(
                    AgentToolCall(
                        id = call.optString("id").ifBlank { "call_${UUID.randomUUID()}" },
                        name = fn.optString("name"),
                        argumentsJson = fn.optString("arguments")
                    )
                )
            }
        }
        if (calls.isNotEmpty()) return OpenAiCompatClient.Completion.Tools(calls, content)
    }
    return OpenAiCompatClient.Completion.Text(content)
}

private fun encodeChatMessages(messages: List<AgentChatMessage>): JSONArray {
    val array = JSONArray()
    messages.forEach { m ->
        val o = JSONObject().put("role", m.role)
        when (m.role) {
            "tool" -> {
                o.put("content", m.content)
                o.put("tool_call_id", m.toolCallId.orEmpty())
                if (!m.toolName.isNullOrBlank()) o.put("name", m.toolName)
            }
            else -> {
                o.put("content", m.content)
                if (m.toolCalls.isNotEmpty()) {
                    val calls = JSONArray()
                    m.toolCalls.forEach { call ->
                        calls.put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put("arguments", call.argumentsJson)
                                )
                        )
                    }
                    o.put("tool_calls", calls)
                }
            }
        }
        array.put(o)
    }
    return array
}

}
