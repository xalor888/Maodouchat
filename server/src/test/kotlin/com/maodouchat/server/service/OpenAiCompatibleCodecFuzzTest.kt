package com.maodouchat.server.service

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Same Json config as production. Only parseToJsonElement is used here,
// so ignoreUnknownKeys/explicitNulls do not affect the parsed values.
private val jsonCodec190 = Json { ignoreUnknownKeys = true; explicitNulls = false }

// Lowercase for the generated role alphabet (same compact table as the
// restrict cluster, renamed: the three test files share one package).
private fun lowerUnit190Codec(c: Char): String = when (c) {
    in 'A'..'Z' -> (c + 32).toString()
    '\u00C9' -> "\u00E9"
    '\u00DC' -> "\u00FC"
    '\u0391' -> "\u03B1"
    '\u03A9' -> "\u03C9"
    '\u0130' -> "i\u0307"
    '\u1E9E' -> "\u00DF"
    '\u212A' -> "k"
    '\u212B' -> "\u00E5"
    '\u03A3' -> "\u03C3"
    else -> c.toString()
}

private fun isCased190Codec(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c == '\u00C9' || c == '\u00E9' ||
        c == '\u00DC' || c == '\u00FC' || c == '\u00DF' || c == '\u0391' ||
        c == '\u03B1' || c == '\u03A9' || c == '\u03C9' || c == '\u03A3' ||
        c == '\u03C3' || c == '\u03C2' || c == '\u0130' || c == '\u1E9E' ||
        c == '\u212A' || c == '\u212B' || c == '\u00E5'

private fun isIgnorable190Codec(c: Char): Boolean =
    c in '0'..'9' || c in '\u0660'..'\u0669' || c in '\uFF10'..'\uFF19' ||
        c == '\u00AA' || c == '\u00BA' || c == '\u01BB' || c in '\u01C0'..'\u01C3'

private fun lower190Codec(s: String): String {
    val units = ArrayList<Int>()
    val src = ArrayList<Int>()
    for (i in s.indices) {
        val low = lowerUnit190Codec(s[i])
        for (ch in low) {
            units.add(ch.code)
            src.add(i)
        }
    }
    for (pos in units.indices) {
        if (units[pos] != 0x03C3) continue
        if (s[src[pos]] != '\u03A3') continue
        var j = src[pos] - 1
        while (j >= 0 && isIgnorable190Codec(s[j])) j--
        val before = j >= 0 && isCased190Codec(s[j])
        j = src[pos] + 1
        while (j < s.length && isIgnorable190Codec(s[j])) j++
        val after = j < s.length && isCased190Codec(s[j])
        if (before && !after) units[pos] = 0x03C2
    }
    return String(units.map { it.toChar() }.toCharArray())
}

private fun chatRoleOracle190(role: String): String = when (lower190Codec(role)) {
    "developer", "system" -> "system"
    "assistant" -> "assistant"
    else -> "user"
}

private fun chatContentOracle190(content: JsonElement): JsonElement = when (content) {
    is JsonPrimitive -> content
    is JsonArray -> JsonArray(content.map(::compatPartOracle190))
    is JsonObject -> JsonArray(listOf(compatPartOracle190(content)))
    else -> JsonPrimitive(content.toString())
}

private fun extractOutputTextOracle190(body: String): String? {
    val root = runCatching { jsonCodec190.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    root["choices"]
        ?.let { it as? JsonArray }
        ?.firstOrNull()
        ?.let { it as? JsonObject }
        ?.get("message")
        ?.let { it as? JsonObject }
        ?.let { message ->
            message["content"]?.let(::flattenContentOracle190)?.takeIf(String::isNotBlank)
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

private fun extractUsageOracle190(body: String): Pair<Long?, Long?>? {
    val root = runCatching { jsonCodec190.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val usage = root["usage"] as? JsonObject ?: return null
    val input = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull
        ?: usage["input_tokens"]?.jsonPrimitive?.longOrNull
    val output = usage["completion_tokens"]?.jsonPrimitive?.longOrNull
        ?: usage["output_tokens"]?.jsonPrimitive?.longOrNull
    return if (input != null || output != null) input to output else null
}

private fun streamDeltaOracle190(event: JsonObject): String? {
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

private fun streamErrorOracle190(event: JsonObject): String? {
    val type = event["type"]?.jsonPrimitive?.contentOrNull
    if (type == "error" || type == "response.failed") {
        return event["message"]?.jsonPrimitive?.contentOrNull
            ?: (event["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "AI 流式响应失败"
    }
    val err = event["error"] as? JsonObject
    return err?.get("message")?.jsonPrimitive?.contentOrNull
}

private fun flattenContentOracle190(content: JsonElement): String? = when (content) {
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

private fun compatPartOracle190(part: JsonElement): JsonElement {
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

// A codec op can throw (JsonPrimitive/JsonObject access on the wrong shape
// throws IllegalArgumentException); compare value + throw side by side.
private fun <T> attempt190(block: () -> T): Pair<T?, String?> {
    return try {
        block() to null
    } catch (e: Exception) {
        null to e.javaClass.simpleName
    }
}

private val ROLE_POOL190 = listOf(
    "system", "developer", "assistant", "user", "SYSTEM", "System",
    "DEVELOPER", "Assistant", "USER", "", "  ", "sys", "ΣYSTEM", "aΣ",
    "Σ", "İ", "user\n", "\u00A0system", "SYSTEM ", "Assistant "
)

private val JSON_KEYS190 = listOf(
    "choices", "message", "content", "delta", "type", "error", "usage",
    "prompt_tokens", "completion_tokens", "input_tokens", "output_tokens",
    "output", "output_text", "text", "role", "image_url", "url", "filename", "image"
)
private val JSON_STRINGS190 = listOf(
    "", " ", "hello", "system", "assistant", "user", "developer", "error",
    "response.output_text.delta", "response.failed", "text", "input_text",
    "image_url", "input_image", "input_file", "document", "hi\nthere", "é",
    "😀", "a\uD800b", "  padded  ", "AI 流式响应失败", "x", "0"
)
private val JSON_NUMBERS190 = listOf(
    "0", "1", "42", "-1", "1.5", "1e3", "1.0", "9007199254740993",
    "9223372036854775807", "1e20", "5e-324", "1E2", "15e-1", "10e-1",
    "0.5", "12345678901234567890", "-0", "2e0", "0e0", "12e-2", "1.50"
)
// NaN/Infinity are NOT in JSON_NUMBERS190: the parser accepts them but
// the encoder refuses to serialize them (non-finite floats are
// prohibited on encode), so random elements must not contain them.
// They are covered as whole-body strings below (parse-only path).
private val MALFORMED_BODIES190 = listOf(
    "", " ", "{", "[1,", "{\"a\":1,}", "{\"a\":}", "{'a':1}", "01", "+1",
    ".5", "1.", "1e", "tru", "nul", "{\"a\":1} x", "01.5",
    "\"\\ud800\"", "\"a\u0001b\"", "{\"a\": 1, \"a\": 2}",
    "NaN", "Infinity", "-Infinity"
)

private fun randomRole190(r: Random): String {
    if (r.nextInt(3) == 0) return ROLE_POOL190.random(r)
    val n = r.nextInt(0, 12)
    return buildString {
        repeat(n) { append("abcxyzSYSTEM_ ".random(r)) }
    }
}

private fun randomJsonElement190(r: Random, depth: Int): JsonElement {
    val kind = if (depth <= 0) r.nextInt(0, 4) else r.nextInt(0, 6)
    return when (kind) {
        0 -> JsonPrimitive(JSON_STRINGS190.random(r))
        1 -> jsonCodec190.parseToJsonElement(JSON_NUMBERS190.random(r))
        2 -> JsonPrimitive(r.nextBoolean())
        3 -> JsonNull
        4 -> {
            val m = LinkedHashMap<String, JsonElement>()
            repeat(r.nextInt(0, 4)) {
                m[JSON_KEYS190.random(r)] = randomJsonElement190(r, depth - 1)
            }
            JsonObject(m)
        }
        else -> {
            val n = r.nextInt(0, 4)
            JsonArray(List(n) { randomJsonElement190(r, depth - 1) })
        }
    }
}

private fun randomBody190(r: Random): String {
    if (r.nextInt(6) == 0) return MALFORMED_BODIES190.random(r)
    val el = randomJsonElement190(r, 3)
    return jsonCodec190.encodeToString(JsonElement.serializer(), el)
}

private fun obj190(body: String): JsonObject =
    jsonCodec190.parseToJsonElement(body).jsonObject

// ---------- tests ----------

class OpenAiCompatibleCodecFuzzTest {

    @Test
    fun codecExtractDifferential190() {
        val r = Random(190005)
        repeat(250) {
            val body = randomBody190(r)
            assertEquals(
                attempt190 { extractOutputTextOracle190(body) },
                attempt190 { OpenAiCompatibleCodec.extractOutputText(body) }
            )
            assertEquals(
                attempt190 { extractUsageOracle190(body) },
                attempt190 { OpenAiCompatibleCodec.extractUsage(body) }
            )
        }
    }

    @Test
    fun codecContentRoleDifferential190() {
        val r = Random(190006)
        repeat(250) {
            val body = randomBody190(r)
            assertEquals(
                attempt190 { chatContentOracle190(jsonCodec190.parseToJsonElement(body)) },
                attempt190 { OpenAiCompatibleCodec.chatContent(jsonCodec190.parseToJsonElement(body)) }
            )
            val role = randomRole190(r)
            assertEquals(
                chatRoleOracle190(role),
                OpenAiCompatibleCodec.chatRole(role)
            )
        }
    }

    @Test
    fun codecStreamPinned190() {
        assertEquals("system", OpenAiCompatibleCodec.chatRole("SYSTEM"))
        assertEquals("system", OpenAiCompatibleCodec.chatRole("developer"))
        assertEquals("assistant", OpenAiCompatibleCodec.chatRole("assistant"))
        assertEquals("user", OpenAiCompatibleCodec.chatRole("whatever"))
        assertEquals("user", OpenAiCompatibleCodec.chatRole(""))
        assertEquals("user", OpenAiCompatibleCodec.chatRole("ΣYSTEM"))
        assertEquals(
            "hello",
            OpenAiCompatibleCodec.extractOutputText(
                """{"choices":[{"message":{"content":"hello"}}]}"""
            )
        )
        assertEquals(
            "ab",
            OpenAiCompatibleCodec.extractOutputText(
                """{"choices":[{"message":{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}]}"""
            )
        )
        assertEquals(
            "hi",
            OpenAiCompatibleCodec.extractOutputText("""{"output_text":"  hi  "}""")
        )
        assertEquals(
            "x\ny\nz",
            OpenAiCompatibleCodec.extractOutputText(
                """{"output":[{"content":[{"text":"x"},{"text":"y"}]},{"content":[{"text":"z"}]}]}"""
            )
        )
        assertEquals(
            null,
            OpenAiCompatibleCodec.extractOutputText(
                """{"choices":[{"message":{"content":"   "}}]}"""
            )
        )
        assertEquals(null, OpenAiCompatibleCodec.extractOutputText("not json"))
        assertEquals(null, OpenAiCompatibleCodec.extractOutputText("{}"))
        assertEquals(
            Pair(10L, 5L),
            OpenAiCompatibleCodec.extractUsage(
                """{"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
            )
        )
        assertEquals(
            Pair(7L, null),
            OpenAiCompatibleCodec.extractUsage("""{"usage":{"input_tokens":7}}""")
        )
        assertEquals(
            Pair(1000L, null),
            OpenAiCompatibleCodec.extractUsage("""{"usage":{"prompt_tokens":"1e3"}}""")
        )
        assertEquals(
            null,
            OpenAiCompatibleCodec.extractUsage("""{"usage":{"prompt_tokens":"1.0"}}""")
        )
        assertEquals(null, OpenAiCompatibleCodec.extractUsage("""{"usage":{}}"""))
        assertEquals(
            null,
            OpenAiCompatibleCodec.extractUsage("""{"usage":{"prompt_tokens":null}}""")
        )
        assertEquals(
            null,
            OpenAiCompatibleCodec.extractUsage("""{"usage":{"prompt_tokens":NaN}}""")
        )
        assertEquals(
            null,
            OpenAiCompatibleCodec.extractOutputText("NaN")
        )
        assertEquals(
            "hel",
            OpenAiCompatibleCodec.streamDelta(
                obj190("""{"choices":[{"delta":{"content":"hel"}}]}""")
            )
        )
        assertEquals(
            "world",
            OpenAiCompatibleCodec.streamDelta(
                obj190("""{"type":"response.output_text.delta","delta":"world"}""")
            )
        )
        assertEquals(null, OpenAiCompatibleCodec.streamDelta(obj190("""{}""")))
        assertEquals(null, OpenAiCompatibleCodec.streamDelta(obj190("""{"type":"error"}""")))
        assertEquals(
            "boom",
            OpenAiCompatibleCodec.streamError(obj190("""{"type":"error","message":"boom"}"""))
        )
        assertEquals(
            "bad",
            OpenAiCompatibleCodec.streamError(
                obj190("""{"type":"response.failed","error":{"message":"bad"}}""")
            )
        )
        assertEquals(
            "AI 流式响应失败",
            OpenAiCompatibleCodec.streamError(obj190("""{"type":"error"}"""))
        )
        assertEquals(
            "m",
            OpenAiCompatibleCodec.streamError(obj190("""{"error":{"message":"m"}}"""))
        )
        assertEquals(null, OpenAiCompatibleCodec.streamError(obj190("""{}""")))
        assertEquals(
            null,
            OpenAiCompatibleCodec.streamError(obj190("""{"type":"other","message":"x"}"""))
        )
        assertEquals(
            JsonPrimitive("x"),
            OpenAiCompatibleCodec.chatContent(JsonPrimitive("x"))
        )
        assertEquals(
            JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("hi"))))),
            OpenAiCompatibleCodec.chatContent(
                JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("hi")))
            )
        )
        // JsonNull IS a JsonPrimitive, so chatContent returns it unchanged
        // (the else branch never sees it).
        assertEquals(
            JsonNull,
            OpenAiCompatibleCodec.chatContent(JsonNull)
        )
        assertEquals(
            JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("image_url"), "image_url" to JsonObject(mapOf("url" to JsonPrimitive("u2"))))))),
            OpenAiCompatibleCodec.chatContent(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("input_image"),
                        "image_url" to JsonObject(mapOf("url" to JsonPrimitive("u2")))
                    )
                )
            )
        )
        // Wrong-shape primitive access throws IllegalArgumentException on
        // both sides (oracle mirrors the same expression shapes).
        assertEquals(
            attempt190 { streamDeltaOracle190(obj190("""{"type":{"x":1}}""")) },
            attempt190 { OpenAiCompatibleCodec.streamDelta(obj190("""{"type":{"x":1}}""")) }
        )
        assertEquals(
            attempt190 {
                streamDeltaOracle190(
                    obj190("""{"choices":[{"delta":{"content":{"x":1}}}]}""")
                )
            },
            attempt190 {
                OpenAiCompatibleCodec.streamDelta(
                    obj190("""{"choices":[{"delta":{"content":{"x":1}}}]}""")
                )
            }
        )
        assertEquals(
            attempt190 { extractUsageOracle190("""{"usage":{"prompt_tokens":{"x":1}}}""") },
            attempt190 { OpenAiCompatibleCodec.extractUsage("""{"usage":{"prompt_tokens":{"x":1}}}""") }
        )
        assertEquals(
            attempt190 { extractOutputTextOracle190("""{"output_text":{"x":1}}""") },
            attempt190 { OpenAiCompatibleCodec.extractOutputText("""{"output_text":{"x":1}}""") }
        )
    }
}
