package com.maodouchat.ai.agent

import org.json.JSONArray
import org.json.JSONObject

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

    fun chatCompletionsBody(
        provider: LocalAiProvider,
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        stream: Boolean
    ): JSONObject =
        LocalAiChatCompletionsCodec.chatCompletionsBody(provider, messages, tools, stream)

    fun responsesBody(
        provider: LocalAiProvider,
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        stream: Boolean
    ): JSONObject =
        LocalAiResponsesEncode.responsesBody(provider, messages, tools, stream)

    fun anthropicBody(
        provider: LocalAiProvider,
        messages: List<AgentChatMessage>,
        tools: List<Map<String, Any?>>?,
        stream: Boolean
    ): JSONObject =
        LocalAiAnthropicEncode.anthropicBody(provider, messages, tools, stream)

    fun parseChatCompletions(payload: String): OpenAiCompatClient.Completion =
        LocalAiChatCompletionsCodec.parseChatCompletions(payload)

    fun parseResponses(payload: String): OpenAiCompatClient.Completion =
        LocalAiResponsesParse.parseResponses(payload)

    fun parseAnthropic(payload: String): OpenAiCompatClient.Completion =
        LocalAiAnthropicParse.parseAnthropic(payload)

    fun parseSseChat(
        payload: String,
        onDelta: (String) -> Unit
    ): OpenAiCompatClient.Completion =
        LocalAiSseCodec.parseSseChat(payload, onDelta)

    internal fun sseDeltaParts(delta: JSONObject?): LocalAiSseCodec.SseDeltaParts =
        LocalAiSseCodec.sseDeltaParts(delta)

    internal fun sseVisibleText(delta: JSONObject?): String =
        LocalAiSseCodec.sseVisibleText(delta)

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
