package com.maodouchat.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Anthropic Messages 协议簇：请求编码 + 响应解析。

internal object LocalAiAnthropicCodec {

fun anthropicBody(
    provider: LocalAiProvider,
    messages: List<AgentChatMessage>,
    tools: List<Map<String, Any?>>?,
    stream: Boolean
): JSONObject {
    val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }.trim()
    val body = JSONObject()
        .put("model", provider.model)
        .put("max_tokens", provider.clampedMaxTokens())
        .put("stream", stream && tools.isNullOrEmpty())
        .put("messages", encodeAnthropicMessages(messages))
    if (system.isNotBlank()) body.put("system", system)
    provider.temperature?.let { body.put("temperature", it.coerceIn(0.0, 1.0)) }
    provider.topP?.let { body.put("top_p", it.coerceIn(0.0, 1.0)) }
    if (!tools.isNullOrEmpty()) {
        body.put("tools", encodeAnthropicTools(tools))
        body.put("stream", false)
    }
    return body
}

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

private fun encodeAnthropicMessages(messages: List<AgentChatMessage>): JSONArray {
    val array = JSONArray()
    var pendingToolResults = JSONArray()
    fun flushTools() {
        if (pendingToolResults.length() == 0) return
        array.put(JSONObject().put("role", "user").put("content", pendingToolResults))
        pendingToolResults = JSONArray()
    }
    messages.filter { it.role != "system" }.forEach { m ->
        when (m.role) {
            "tool" -> {
                pendingToolResults.put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", m.toolCallId.orEmpty())
                        .put("content", m.content)
                )
            }
            "assistant" -> {
                flushTools()
                val content = JSONArray()
                if (m.content.isNotBlank()) {
                    content.put(JSONObject().put("type", "text").put("text", m.content))
                }
                m.toolCalls.forEach { call ->
                    val input = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }
                        .getOrElse { JSONObject() }
                    content.put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", call.id)
                            .put("name", call.name)
                            .put("input", input)
                    )
                }
                if (content.length() == 0) {
                    content.put(JSONObject().put("type", "text").put("text", ""))
                }
                array.put(JSONObject().put("role", "assistant").put("content", content))
            }
            else -> {
                flushTools()
                array.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", m.content)
                )
            }
        }
    }
    flushTools()
    return array
}

private fun encodeAnthropicTools(tools: List<Map<String, Any?>>): JSONArray {
    val array = JSONArray()
    tools.forEach { spec ->
        val fn = spec["function"] as? Map<*, *> ?: return@forEach
        array.put(
            JSONObject()
                .put("name", fn["name"])
                .put("description", fn["description"])
                .put("input_schema", LocalAiProtocolCodec.toJson(fn["parameters"]))
        )
    }
    return array
}

}
