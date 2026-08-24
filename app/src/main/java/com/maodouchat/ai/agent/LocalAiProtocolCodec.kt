package com.maodouchat.ai.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Encodes chat + tools for OpenAI Chat Completions, OpenAI Responses, and Anthropic Messages.
 * No network. Keys stay on the caller.
 */
object LocalAiProtocolCodec {

    fun parseProtocol(raw: String?): LocalAiProtocol =
        when (raw?.trim()?.uppercase()) {
            "OPENAI_RESPONSES", "RESPONSES", "RESPONSE" -> LocalAiProtocol.OPENAI_RESPONSES
            "ANTHROPIC_MESSAGES", "ANTHROPIC", "CLAUDE" -> LocalAiProtocol.ANTHROPIC_MESSAGES
            else -> LocalAiProtocol.OPENAI_CHAT_COMPLETIONS
        }

    fun extraHeaders(provider: LocalAiProvider): Map<String, String> {
        val raw = provider.extraHeadersJson.ifBlank { "{}" }
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            obj.keys().forEach { key ->
                val value = obj.optString(key).trim()
                if (key.isNotBlank() && value.isNotBlank()) put(key, value)
            }
        }
    }

    fun applySampling(body: JSONObject, provider: LocalAiProvider) {
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
            body.put("tools", JSONArray(tools.map { toJson(it) }))
            body.put("tool_choice", "auto")
            body.put("stream", false)
        }
        return body
    }

    fun responsesBody(
        provider: LocalAiProvider,
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        stream: Boolean
    ): JSONObject {
        val body = JSONObject()
            .put("model", provider.model)
            .put("stream", stream && tools.isNullOrEmpty())
            .put("input", encodeResponsesInput(messages))
            .put("max_output_tokens", provider.clampedMaxTokens())
        provider.temperature?.let { body.put("temperature", it.coerceIn(0.0, 2.0)) }
        provider.topP?.let { body.put("top_p", it.coerceIn(0.0, 1.0)) }
        if (!tools.isNullOrEmpty()) {
            body.put("tools", encodeResponsesTools(tools))
            body.put("tool_choice", "auto")
            body.put("stream", false)
        }
        return body
    }

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

    fun parseChatCompletions(payload: String): OpenAiCompatClient.Completion {
        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: return OpenAiCompatClient.Completion.Error("模型返回不是 JSON")
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: return OpenAiCompatClient.Completion.Error("模型没有 choices")
        val message = choice.optJSONObject("message") ?: JSONObject()
        return parseOpenAiMessage(message)
    }

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

    fun parseSseChat(payload: String, onDelta: (String) -> Unit): OpenAiCompatClient.Completion {
        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        payload.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) return@forEach
            val data = trimmed.removePrefix("data:").trim()
            if (data == "[DONE]" || data.isBlank()) return@forEach
            val parsed = runCatching {
                val obj = JSONObject(data)
                val choiceDelta = obj.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                val visible = sseDeltaParts(choiceDelta)
                if (visible.content.isEmpty() && visible.reasoning.isEmpty()) {
                    val fallback = obj.optJSONArray("delta")
                        ?.optJSONObject(0)
                        ?.optJSONObject("text")
                        ?.optString("text")
                        .orEmpty()
                        .ifEmpty {
                            obj.optJSONObject("delta")
                                ?.optJSONArray("text")
                                ?.optJSONObject(0)
                                ?.optString("text")
                                .orEmpty()
                        }
                    SseDeltaParts(content = fallback)
                } else {
                    visible
                }
            }.getOrNull() ?: SseDeltaParts()
            if (parsed.content.isNotEmpty()) {
                contentBuilder.append(parsed.content)
                onDelta(parsed.content)
            } else if (parsed.reasoning.isNotEmpty() && contentBuilder.isEmpty()) {
                reasoningBuilder.append(parsed.reasoning)
            }
        }
        val text = contentBuilder.toString().ifBlank { reasoningBuilder.toString() }
        if (text.isBlank()) return OpenAiCompatClient.Completion.Error("empty stream")
        if (contentBuilder.isBlank() && reasoningBuilder.isNotBlank()) {
            onDelta(text)
        }
        return OpenAiCompatClient.Completion.Text(text)
    }

    internal data class SseDeltaParts(
        val content: String = "",
        val reasoning: String = ""
    )

    /**
     * Split Chat Completions SSE deltas. OpenCode zen (and similar gateways)
     * stream `reasoning_content` first. [JSONObject.optString] returns "" for a
     * missing key, which must not mask later fallbacks or look like empty content.
     */
    internal fun sseDeltaParts(delta: JSONObject?): SseDeltaParts {
        if (delta == null) return SseDeltaParts()
        val content = delta.opt("content")
        val reasoning = delta.opt("reasoning_content")
        return SseDeltaParts(
            content = if (content is String) content else "",
            reasoning = if (reasoning is String) reasoning else ""
        )
    }

    internal fun sseVisibleText(delta: JSONObject?): String {
        val parts = sseDeltaParts(delta)
        return parts.content.ifEmpty { parts.reasoning }
    }

    internal fun encodeChatMessages(messages: List<AgentChatMessage>): JSONArray {
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

    private fun encodeResponsesInput(messages: List<AgentChatMessage>): JSONArray {
        val array = JSONArray()
        messages.forEach { m ->
            when (m.role) {
                "tool" -> array.put(
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", m.toolCallId.orEmpty())
                        .put("output", m.content)
                )
                "assistant" -> {
                    if (m.toolCalls.isNotEmpty()) {
                        m.toolCalls.forEach { call ->
                            array.put(
                                JSONObject()
                                    .put("type", "function_call")
                                    .put("call_id", call.id)
                                    .put("name", call.name)
                                    .put("arguments", call.argumentsJson)
                            )
                        }
                    }
                    if (m.content.isNotBlank()) {
                        array.put(JSONObject().put("role", "assistant").put("content", m.content))
                    }
                }
                else -> array.put(JSONObject().put("role", m.role).put("content", m.content))
            }
        }
        return array
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

    private fun encodeResponsesTools(tools: List<Map<String, Any?>>): JSONArray {
        val array = JSONArray()
        tools.forEach { spec ->
            val fn = spec["function"] as? Map<*, *> ?: return@forEach
            array.put(
                JSONObject()
                    .put("type", "function")
                    .put("name", fn["name"])
                    .put("description", fn["description"])
                    .put("parameters", toJson(fn["parameters"]))
            )
        }
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
                    .put("input_schema", toJson(fn["parameters"]))
            )
        }
        return array
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

    @Suppress("UNCHECKED_CAST")
    fun toJson(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> value
        is Map<*, *> -> {
            val o = JSONObject()
            value.forEach { (k, v) -> o.put(k.toString(), toJson(v)) }
            o
        }
        is List<*> -> {
            val a = JSONArray()
            value.forEach { a.put(toJson(it)) }
            a
        }
        else -> value
    }
}
