package com.maodouchat.domain.messaging

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

private val JSON199c = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr199c(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

// 手动重述 ContentEnvelope 的接受规则：成功路径只认完整合法的信封，
// 其余一律走 fail-safe（与生产同一形状，含 jsonPrimitive 会抛的边角）。
private fun asStr199c(el: JsonElement?): String? {
    val p = el as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

private fun asInt199c(el: JsonElement?): Int? {
    val p = el as? JsonPrimitive ?: return null
    val s = p.content
    if (s.contains('.')) return null
    // 整数规则（探针实测，数字与字符串同款）：-?digits 配可选指数，
    // 值须为整数且在 Int 范围内；"007"=7、"1e2"=100，" 1"/"+1"/"1.0" 拒收。
    if (!s.matches(Regex("-?[0-9]+([eE][+-]?[0-9]+)?"))) return null
    val bd = runCatching { java.math.BigDecimal(s) }.getOrNull() ?: return null
    if (bd.stripTrailingZeros().scale() > 0) return null
    return runCatching { bd.intValueExact() }.getOrNull()
}

private fun asLong199c(el: JsonElement?): Long? {
    val p = el as? JsonPrimitive ?: return null
    val s = p.content
    if (s.contains('.')) return null
    if (!s.matches(Regex("-?[0-9]+([eE][+-]?[0-9]+)?"))) return null
    val bd = runCatching { java.math.BigDecimal(s) }.getOrNull() ?: return null
    if (bd.stripTrailingZeros().scale() > 0) return null
    return runCatching { bd.longValueExact() }.getOrNull()
}

private fun asDouble199c(el: JsonElement?): Double? {
    val p = el as? JsonPrimitive ?: return null
    // 字符串也收：解码走 Java parseDouble 语义（探针实测）——去空白、
    // 接受 f/d 后缀与十六进制浮点；"31.23" 字符串即 31.23。
    // 1e400 这类溢出成无穷大的字面量会被解码拒绝。
    val d = p.content.toDoubleOrNull() ?: return null
    return if (d.isFinite()) d else null
}

private fun asStrList199c(el: JsonElement?): List<String>? {
    val arr = el as? JsonArray ?: return null
    val out = ArrayList<String>()
    for (e in arr) out += asStr199c(e) ?: return null
    return out
}

private fun kind199c(s: String): AttachmentKind? = when (s) {
    "image" -> AttachmentKind.IMAGE
    "video" -> AttachmentKind.VIDEO
    "voice" -> AttachmentKind.VOICE
    "file" -> AttachmentKind.FILE
    "sticker" -> AttachmentKind.STICKER
    "gif" -> AttachmentKind.GIF
    "location" -> AttachmentKind.LOCATION
    "contact" -> AttachmentKind.CONTACT
    else -> null
}

private fun decodePayload199c(obj: JsonObject): ContentPayload? {
    val type = asStr199c(obj["type"]) ?: return null
    return when (type) {
        "text" -> {
            val text = asStr199c(obj["text"]) ?: return null
            val mentions = obj["mentions"]?.let { m ->
                val arr = m as? JsonArray ?: return null
                arr.map { e ->
                    val o = e as? JsonObject ?: return null
                    Mention(asStr199c(o["userId"]) ?: return null, asStr199c(o["displayName"]) ?: return null)
                }
            } ?: emptyList()
            ContentPayload.Text(text, mentions)
        }
        "reply" -> {
            val id = asStr199c(obj["replyToMessageId"]) ?: return null
            val inner = obj["content"] as? JsonObject ?: return null
            ContentPayload.Reply(id, decodePayload199c(inner) ?: return null)
        }
        "attachment" -> {
            val kind = kind199c(asStr199c(obj["kind"]) ?: return null) ?: return null
            val id = asStr199c(obj["attachmentId"]) ?: return null
            val fileName = when (val e = obj["fileName"]) {
                null, is JsonNull -> null
                else -> asStr199c(e) ?: return null
            }
            val mimeType = when (val e = obj["mimeType"]) {
                null, is JsonNull -> null
                else -> asStr199c(e) ?: return null
            }
            val sizeBytes = when (val e = obj["sizeBytes"]) {
                null, is JsonNull -> null
                else -> asLong199c(e) ?: return null
            }
            val durationMs = when (val e = obj["durationMs"]) {
                null, is JsonNull -> null
                else -> asLong199c(e) ?: return null
            }
            ContentPayload.Attachment(kind, id, fileName, mimeType, sizeBytes, durationMs)
        }
        "location" -> {
            val lat = asDouble199c(obj["latitude"]) ?: return null
            val lon = asDouble199c(obj["longitude"]) ?: return null
            ContentPayload.Location(lat, lon)
        }
        "contact" -> ContentPayload.Contact(
            asStr199c(obj["userId"]) ?: return null,
            asStr199c(obj["displayName"]) ?: return null,
        )
        "poll" -> ContentPayload.Poll(
            asStr199c(obj["pollId"]) ?: return null,
            asStr199c(obj["question"]) ?: return null,
            asStrList199c(obj["options"]) ?: return null,
        )
        "system" -> {
            val eventType = asStr199c(obj["eventType"]) ?: return null
            val data = when (val e = obj["data"]) {
                null, is JsonNull -> null
                else -> asStr199c(e) ?: return null
            }
            ContentPayload.SystemEvent(eventType, data)
        }
        "unknown" -> ContentPayload.Unknown(
            asStr199c(obj["rawType"]) ?: return null,
            when (val e = obj["rawPayload"]) {
                null -> ""
                is JsonNull -> return null
                else -> asStr199c(e) ?: return null
            },
        )
        else -> null
    }
}

private fun envelopeOracle199c(raw: String): String {
    val root = runCatching { JSON199c.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    if (root != null) {
        val versionEl = root["version"]
        val version = if (versionEl == null) 1 else asInt199c(versionEl)
        val contentObj = root["content"] as? JsonObject
        if (version != null && contentObj != null) {
            val payload = decodePayload199c(contentObj)
            if (payload != null) return JSON199c.encodeToString(ContentEnvelope(version, payload))
        }
    }
    val obj = runCatching { JSON199c.parseToJsonElement(raw).jsonObject }.getOrNull()
    val content = obj?.get("content") as? JsonObject
    val rawType = content?.get("type")?.jsonPrimitive?.content ?: "unknown"
    return JSON199c.encodeToString(
        ContentEnvelope(
            version = obj?.get("version")?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            content = ContentPayload.Unknown(rawType = rawType, rawPayload = content?.toString() ?: raw),
        ),
    )
}

private fun envelopeProd199c(raw: String): String =
    JSON199c.encodeToString(decodeContentEnvelopeFailSafe(JSON199c, raw))

// op 固定为 env：case 只带原始 JSON 串。
private data class EnvelopeCase199c(
    val raw: String,
)

private fun q199c(s: String): String = JSON199c.encodeToString(s)

private val ENV_TEXTS199c = listOf(
    "",
    "hi",
    "hello @Alice",
    "a\"b",
    "line\nbreak",
    "tab\there",
    "emoji \uD83D\uDE00",
    "ünïcödé",
    "back\\slash",
    "𝄞 music",
    "\u0001ctrl",
    "x",
    "  spaced  ",
    "null",
    "true",
)

private val ENV_NUMS199c = listOf(
    "0", "1.5", "-12.25", "31.23", "121.47", "90", "-180", "1e2", "1e-7", "0.1", "3.141592653589793",
)

private fun randomPayloadJson199c(r: Random, depth: Int): String {
    val kind = r.nextInt(8)
    val mutate = r.nextInt(4) == 0
    val body = when (kind) {
        0 -> "\"type\":\"text\",\"text\":${q199c(ENV_TEXTS199c[r.nextInt(ENV_TEXTS199c.size)])}" +
            if (r.nextBoolean()) ",\"mentions\":[{\"userId\":\"u1\",\"displayName\":\"Alice\"}]" else ""
        1 -> "\"type\":\"reply\",\"replyToMessageId\":\"m_${r.nextInt(100)}\"," +
            "\"content\":${if (depth > 0) randomPayloadJson199c(r, depth - 1) else "{\"type\":\"text\",\"text\":\"inner\"}"}"
        2 -> "\"type\":\"attachment\",\"kind\":${q199c(listOf("image", "video", "voice", "file", "sticker", "gif", "location", "contact")[r.nextInt(8)])}," +
            "\"attachmentId\":\"att_${r.nextInt(100)}\"" +
            if (r.nextBoolean()) ",\"fileName\":\"pic.jpg\",\"sizeBytes\":${r.nextInt(100000)}" else ""
        3 -> "\"type\":\"location\",\"latitude\":${ENV_NUMS199c[r.nextInt(ENV_NUMS199c.size)]}," +
            "\"longitude\":${ENV_NUMS199c[r.nextInt(ENV_NUMS199c.size)]}"
        4 -> "\"type\":\"contact\",\"userId\":\"u${r.nextInt(10)}\",\"displayName\":${q199c(ENV_TEXTS199c[r.nextInt(ENV_TEXTS199c.size)])}"
        5 -> "\"type\":\"poll\",\"pollId\":\"p${r.nextInt(10)}\",\"question\":\"q?\"," +
            "\"options\":[${q199c("a")},${q199c("b")}]"
        6 -> "\"type\":\"system\",\"eventType\":\"join\"" +
            if (r.nextBoolean()) ",\"data\":${q199c("d")}" else ""
        else -> "\"type\":\"unknown\",\"rawType\":\"future_v9\",\"rawPayload\":${q199c("{\"x\":1}")}"
    }
    val mutated = when {
        !mutate -> body
        r.nextBoolean() -> body.replaceFirst("\"type\":", "\"typo\":")
        r.nextBoolean() -> body + ",\"futureField\":123"
        else -> body.replace(Regex("\"text\":[^,}]+"), "\"text\":42")
    }
    return "{$mutated}"
}

private fun randomEnvelopeCase199c(r: Random): EnvelopeCase199c {
    return when (r.nextInt(10)) {
        0 -> EnvelopeCase199c("{\"version\":${listOf(1, 2, 0, -1)[r.nextInt(4)]},\"content\":${randomPayloadJson199c(r, 2)}}")
        1 -> EnvelopeCase199c("{\"content\":${randomPayloadJson199c(r, 2)}}")
        2 -> EnvelopeCase199c("{\"version\":${listOf("1.0", "\"1\"", "null", "{}", "2147483648", "1e2")[r.nextInt(6)]},\"content\":${randomPayloadJson199c(r, 1)}}")
        3 -> EnvelopeCase199c("{\"version\":1,\"content\":{\"type\":${q199c(listOf("futureType_v9", "Text", "TEXT", "", "42")[r.nextInt(5)])},\"someField\":42}}")
        4 -> EnvelopeCase199c(listOf("", " ", "not json at all", "{", "[1,2]", "\"str\"", "123", "null", "true")[r.nextInt(9)])
        5 -> EnvelopeCase199c("{\"version\":1,\"content\":${listOf("null", "\"x\"", "42", "[]")[r.nextInt(4)]}}")
        6 -> EnvelopeCase199c("{\"version\":1}")
        7 -> EnvelopeCase199c("{\"version\":1,\"content\":${randomPayloadJson199c(r, 2)},\"extra\":true}")
        8 -> EnvelopeCase199c("{\"version\":1,\"content\":${randomPayloadJson199c(r, 2)}} trailing")
        else -> EnvelopeCase199c("{\"version\":1,\"content\":${randomPayloadJson199c(r, 2)}}")
    }
}

private fun boundaryEnvelopeCase199c(r: Random): EnvelopeCase199c {
    val raws = listOf(
        "",
        "not json at all",
        "{}",
        "[]",
        "{\"version\":1}",
        "{\"content\":null}",
        "{\"version\":1,\"content\":null}",
        "{\"version\":1,\"content\":{\"type\":{}}}",
        "{\"version\":{},\"content\":{\"type\":\"text\",\"text\":\"x\"}}",
        "{\"version\":null,\"content\":{\"type\":\"text\",\"text\":\"x\"}}",
        "{\"version\":\"1\",\"content\":{\"type\":\"text\",\"text\":\"x\"}}",
        "{\"version\":1.0,\"content\":{\"type\":\"text\",\"text\":\"x\"}}",
        "{\"version\":2147483648,\"content\":{\"type\":\"text\",\"text\":\"x\"}}",
        "{\"version\":1,\"content\":{\"type\":\"unknown\",\"rawType\":\"future\"}}",
        "{\"version\":1,\"content\":{\"type\":\"unknown\"}}",
        "{\"version\":1,\"content\":{\"type\":\"text\",\"text\":\"x\",\"mentions\":null}}",
        "{\"version\":1,\"content\":{\"type\":\"text\"}}",
        "{\"version\":1,\"content\":{\"type\":\"attachment\",\"kind\":\"bogus\",\"attachmentId\":\"a\"}}",
        "{\"version\":1,\"content\":{\"type\":\"attachment\",\"kind\":\"image\",\"attachmentId\":\"a\",\"sizeBytes\":1.5}}",
        "{\"version\":1,\"content\":{\"type\":\"location\",\"latitude\":1e400,\"longitude\":0}}",
        "{\"version\":1,\"content\":{\"type\":\"location\",\"latitude\":\"31.23\",\"longitude\":0}}",
        "{\"version\":1,\"content\":{\"type\":\"poll\",\"pollId\":\"p\",\"question\":\"q\",\"options\":[]}}",
        "{\"version\":1,\"content\":{\"type\":\"poll\",\"pollId\":\"p\",\"question\":\"q\",\"options\":[1]}}",
        "{\"version\":1,\"content\":{\"type\":\"reply\",\"replyToMessageId\":\"m\",\"content\":{\"type\":\"text\",\"text\":\"in\"}}}",
        "{\"version\":1,\"content\":{\"type\":\"reply\",\"replyToMessageId\":\"m\",\"content\":{\"type\":\"nope\"}}}",
        "{\"version\":1,\"content\":{\"type\":\"text\",\"text\":\"x\",\"text\":\"y\"}}",
        "{\"version\":1,\"content\":{\"type\":\"text\",\"text\":\"\\ud800\"}}",
        "{\"version\":1,\"content\":{\"type\":\"system\",\"eventType\":\"join\",\"data\":null}}",
        "{\"version\":2,\"content\":{\"type\":\"contact\",\"userId\":\"u\",\"displayName\":\"Bob\"}}",
        "{\"version\":1,\"content\":{\"type\":\"text\",\"text\":\"x\"},\"version\":2}",
    )
    return EnvelopeCase199c(raws[r.nextInt(raws.size)])
}

// ---------- tests ----------

// oracle 与生产都可能抛 IllegalArgumentException（如 type/version 为对象/数组时
// fail-safe 里的 jsonPrimitive 抛错），与驱动 harness 同款记法比较抛错类名。
private fun outcome199c(block: () -> String): String {
    return try {
        block()
    } catch (e: Exception) {
        "THROW:" + e.javaClass.simpleName
    }
}

class ContentEnvelopeFuzzTest {

    @Test
    fun envelopeDifferential199() {
        val r = Random(199301)
        repeat(300) {
            val c = randomEnvelopeCase199c(r)
            assertEquals(outcome199c { envelopeOracle199c(c.raw) }, outcome199c { envelopeProd199c(c.raw) })
        }
    }

    @Test
    fun envelopeBoundaryDifferential199() {
        val r = Random(199302)
        repeat(300) {
            val c = boundaryEnvelopeCase199c(r)
            assertEquals(outcome199c { envelopeOracle199c(c.raw) }, outcome199c { envelopeProd199c(c.raw) })
        }
    }

    @Test
    fun envelopePinned199() {
        val cases = listOf(
            "{\"version\":1,\"content\":{\"type\":\"text\",\"text\":\"hi\",\"futureField\":123}}",
            "{\"version\":1,\"content\":{\"type\":\"futureType_v9\",\"someField\":42}}",
            "not json at all",
            "{\"version\":1,\"content\":{\"type\":\"text\",\"text\":\"x\"}}",
            "{\"version\":1,\"content\":{\"type\":\"location\",\"latitude\":31.23,\"longitude\":121.47}}",
            "{\"version\":1,\"content\":{\"type\":\"unknown\",\"rawType\":\"future\"}}",
            "{\"version\":\"1\",\"content\":{\"type\":\"text\",\"text\":\"x\"}}",
            "{\"version\":1,\"content\":{\"type\":{\"a\":1}}}",
        )
        for (raw in cases) {
            assertEquals(outcome199c { envelopeOracle199c(raw) }, outcome199c { envelopeProd199c(raw) })
        }
    }
}
