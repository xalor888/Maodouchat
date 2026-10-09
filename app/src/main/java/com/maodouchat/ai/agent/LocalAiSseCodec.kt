package com.maodouchat.ai.agent

import org.json.JSONObject

// SSE 流式解析簇。

internal object LocalAiSseCodec {

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

}
