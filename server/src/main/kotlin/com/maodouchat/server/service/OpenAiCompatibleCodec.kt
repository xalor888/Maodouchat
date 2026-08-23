package com.maodouchat.server.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * OpenAI-compatible /chat/completions codec used by SiliconFlow and similar gateways.
 * Responses API (/responses, output_text) is parsed only as a fallback.
 */
internal object OpenAiCompatibleCodec {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun chatRole(role: String): String = when (role.lowercase()) {
        "developer", "system" -> "system"
        "assistant" -> "assistant"
        else -> "user"
    }

    fun chatContent(content: JsonElement): JsonElement = when (content) {
        is JsonPrimitive -> content
        is JsonArray -> JsonArray(content.map(::compatPart))
        is JsonObject -> JsonArray(listOf(compatPart(content)))
        else -> JsonPrimitive(content.toString())
    }

    fun extractOutputText(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        root["choices"]
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("message")
            ?.let { it as? JsonObject }
            ?.let { message ->
                message["content"]?.let(::flattenContent)?.takeIf(String::isNotBlank)
            }
            ?.let { return it }
        root["output_text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        return root["output"]
            ?.let { it as? JsonArray }
            ?.flatMap { outputItem ->
                (outputItem as? JsonObject)
                    ?.get("content")
                    ?.let { it as? JsonArray }
                    ?.mapNotNull { contentItem ->
                        val contentObject = contentItem as? JsonObject
                        contentObject?.get("text")?.jsonPrimitive?.contentOrNull
                            ?: contentObject?.get("output_text")?.jsonPrimitive?.contentOrNull
                    }
                    .orEmpty()
            }
            ?.joinToString("\n")
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    fun extractUsage(body: String): Pair<Long?, Long?>? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val usage = root["usage"] as? JsonObject ?: return null
        val input = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull
            ?: usage["input_tokens"]?.jsonPrimitive?.longOrNull
        val output = usage["completion_tokens"]?.jsonPrimitive?.longOrNull
            ?: usage["output_tokens"]?.jsonPrimitive?.longOrNull
        return if (input != null || output != null) input to output else null
    }

    fun streamDelta(event: JsonObject): String? {
        event["choices"]
            ?.let { it as? JsonArray }
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("delta")
            ?.let { it as? JsonObject }
            ?.get("content")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf(String::isNotEmpty)
            ?.let { return it }
        if (event["type"]?.jsonPrimitive?.contentOrNull == "response.output_text.delta") {
            return event["delta"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
        }
        return null
    }

    fun streamError(event: JsonObject): String? {
        val type = event["type"]?.jsonPrimitive?.contentOrNull
        if (type == "error" || type == "response.failed") {
            return event["message"]?.jsonPrimitive?.contentOrNull
                ?: (event["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: "AI 流式响应失败"
        }
        val err = event["error"] as? JsonObject
        return err?.get("message")?.jsonPrimitive?.contentOrNull
    }

    private fun flattenContent(content: JsonElement): String? = when (content) {
        is JsonPrimitive -> content.contentOrNull?.trim()?.takeIf(String::isNotBlank)
        is JsonArray -> content.mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull
                is JsonObject -> part["text"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.joinToString("").trim().takeIf(String::isNotBlank)
        else -> null
    }

    private fun compatPart(part: JsonElement): JsonElement {
        val obj = part as? JsonObject ?: return part
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        return when (type) {
            "input_text", "text" -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                )
            )
            "input_image", "image_url" -> {
                val url = when (val image = obj["image_url"]) {
                    is JsonPrimitive -> image.content
                    is JsonObject -> image["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    else -> obj["image"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("image_url"),
                        "image_url" to JsonObject(mapOf("url" to JsonPrimitive(url)))
                    )
                )
            }
            "input_file" -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive(
                        "Attached file ${obj["filename"]?.jsonPrimitive?.contentOrNull ?: "document"}."
                    )
                )
            )
            else -> obj
        }
    }
}

@Serializable
internal data class ChatCompletionsRequest(
    val model: String,
    val messages: List<ChatCompletionsMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val stream: Boolean = false
)

@Serializable
internal data class ChatCompletionsMessage(
    val role: String,
    val content: JsonElement
)
