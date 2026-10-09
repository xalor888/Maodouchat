package com.maodouchat.ai.agent

import org.json.JSONArray
import org.json.JSONObject

// OpenAI Responses 请求编码簇。

internal object LocalAiResponsesEncode {

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

    private fun encodeResponsesTools(tools: List<Map<String, Any?>>): JSONArray {
        val array = JSONArray()
        tools.forEach { spec ->
            val fn = spec["function"] as? Map<*, *> ?: return@forEach
            array.put(
                JSONObject()
                    .put("type", "function")
                    .put("name", fn["name"])
                    .put("description", fn["description"])
                    .put("parameters", LocalAiProtocolCodec.toJson(fn["parameters"]))
            )
        }
        return array
    }
}
