package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import java.util.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// unescapeHtmlEntities restated by hand: numeric decimal entities,
// then numeric hex entities, then the named replacements in order.
// A code outside 1..0x10FFFF leaves the entity untouched; a lone
// surrogate code point becomes one lone surrogate unit, exactly as
// String(intArrayOf(code), 0, 1) builds it.
internal val NUMERIC_DECIMAL175 = Regex("&#(\\d{1,7});")
internal val NUMERIC_HEX175 = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)

internal fun unescapeHtmlEntitiesOracle175(raw: String): String {
    if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
    var out = raw
    out = NUMERIC_DECIMAL175.replace(out) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    out = NUMERIC_HEX175.replace(out) { match ->
        match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    return out
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

// parsedContent restated by hand: strip at the last <meta>, then
// unescape; unlike a preview it does not trim.
internal fun parsedContentOracle175(content: String): String =
    content.lastIndexOf(Message.META_TAG_PREFIX).let { idx ->
        val body = if (idx < 0) content else content.substring(0, idx)
        unescapeHtmlEntitiesOracle175(body)
    }

// Strict JSON values, restated by hand for the meta fileName read:
// kotlinx.serialization's parser is strict (no single quotes, no
// trailing commas, no unquoted keys), duplicate keys keep the last
// value, and a number or boolean fileName keeps its literal text.
internal sealed interface JsonValue175
internal data class JsonObject175(val members: Map<String, JsonValue175>) : JsonValue175
internal data class JsonArray175(val items: List<JsonValue175>) : JsonValue175
internal data class JsonString175(val value: String) : JsonValue175
internal data class JsonNumber175(val literal: String) : JsonValue175
internal data class JsonBool175(val value: Boolean) : JsonValue175
internal object JsonNull175 : JsonValue175

internal class JsonParser175(val s: String) {
    var i: Int = 0

    fun skipWs() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    fun parse(): JsonValue175 {
        skipWs()
        val v = parseValue()
        skipWs()
        if (i != s.length) error("trailing content")
        return v
    }

    fun parseValue(): JsonValue175 {
        if (i >= s.length) error("unexpected end")
        return when (val c = s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonString175(parseString())
            't' -> parseLiteral("true", JsonBool175(true))
            'f' -> parseLiteral("false", JsonBool175(false))
            'n' -> parseLiteral("null", JsonNull175)
            '-', in '0'..'9' -> JsonNumber175(parseNumber())
            else -> error("unexpected char $c")
        }
    }

    fun parseObject(): JsonObject175 {
        i++
        val map = LinkedHashMap<String, JsonValue175>()
        skipWs()
        if (i < s.length && s[i] == '}') {
            i++
            return JsonObject175(map)
        }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') error("object key")
            val key = parseString()
            skipWs()
            if (i >= s.length || s[i] != ':') error("object colon")
            i++
            skipWs()
            map[key] = parseValue()
            skipWs()
            if (i >= s.length) error("object end")
            when (s[i]) {
                ',' -> {
                    i++
                    continue
                }
                '}' -> {
                    i++
                    return JsonObject175(map)
                }
                else -> error("object separator")
            }
        }
    }

    fun parseArray(): JsonArray175 {
        i++
        val list = ArrayList<JsonValue175>()
        skipWs()
        if (i < s.length && s[i] == ']') {
            i++
            return JsonArray175(list)
        }
        while (true) {
            skipWs()
            list.add(parseValue())
            skipWs()
            if (i >= s.length) error("array end")
            when (s[i]) {
                ',' -> {
                    i++
                    continue
                }
                ']' -> {
                    i++
                    return JsonArray175(list)
                }
                else -> error("array separator")
            }
        }
    }

    fun parseString(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) error("unterminated string")
            val c = s[i]
            when {
                c == '"' -> {
                    i++
                    return sb.toString()
                }
                c.code < 0x20 -> error("control char in string")
                c == '\\' -> {
                    i++
                    if (i >= s.length) error("unterminated escape")
                    when (val e = s[i]) {
                        '"' -> {
                            sb.append('"')
                            i++
                        }
                        '\\' -> {
                            sb.append('\\')
                            i++
                        }
                        '/' -> {
                            sb.append('/')
                            i++
                        }
                        'b' -> {
                            sb.append('\b')
                            i++
                        }
                        'f' -> {
                            sb.append('\u000C')
                            i++
                        }
                        'n' -> {
                            sb.append('\n')
                            i++
                        }
                        'r' -> {
                            sb.append('\r')
                            i++
                        }
                        't' -> {
                            sb.append('\t')
                            i++
                        }
                        'u' -> {
                            i++
                            val code = readHex4()
                            if (code in 0xD800..0xDBFF && i + 1 < s.length &&
                                s[i] == '\\' && s[i + 1] == 'u'
                            ) {
                                val save = i
                                i += 2
                                val low = readHex4()
                                if (low in 0xDC00..0xDFFF) {
                                    sb.append(code.toChar())
                                    sb.append(low.toChar())
                                } else {
                                    sb.append(code.toChar())
                                    i = save
                                }
                            } else {
                                sb.append(code.toChar())
                            }
                        }
                        else -> error("bad escape")
                    }
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
    }

    fun readHex4(): Int {
        if (i + 4 > s.length) error("short unicode escape")
        var v = 0
        repeat(4) {
            val d = s[i].digitToIntOrNull(16) ?: error("bad hex")
            v = v * 16 + d
            i++
        }
        return v
    }

    fun parseNumber(): String {
        val start = i
        if (i < s.length && s[i] == '-') i++
        if (i >= s.length) error("bad number")
        if (s[i] == '0') i++
        else if (s[i] in '1'..'9') {
            while (i < s.length && s[i] in '0'..'9') i++
        } else error("bad number")
        if (i < s.length && s[i] == '.') {
            i++
            if (i >= s.length || s[i] !in '0'..'9') error("bad fraction")
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || s[i] !in '0'..'9') error("bad exponent")
            while (i < s.length && s[i] in '0'..'9') i++
        }
        return s.substring(start, i)
    }

    fun parseLiteral(expected: String, value: JsonValue175): JsonValue175 {
        if (!s.startsWith(expected, i)) error("bad literal")
        i += expected.length
        return value
    }
}

// parsedMeta's fileName restated by hand: no <meta> tag means the
// message's own meta; a blank tag body means an empty meta; anything
// unparsable (or not an object) falls back to the message's own meta.
// Only a JSON string, number or boolean lands as the name; null,
// objects and arrays read as no name.
internal fun parsedMetaFileNameOracle175(content: String, fallback: String?): String? {
    val idx = content.lastIndexOf(Message.META_TAG_PREFIX)
    if (idx < 0) return fallback
    val json = content.substring(idx + Message.META_TAG_PREFIX.length).substringBefore("</meta>")
    if (json.isBlank()) return null
    return try {
        val value = JsonParser175(json).parse()
        val obj = value as? JsonObject175 ?: return fallback
        when (val v = obj.members["fileName"]) {
            null -> null
            is JsonString175 -> v.value
            is JsonNumber175 -> v.literal
            is JsonBool175 -> if (v.value) "true" else "false"
            is JsonNull175 -> null
            else -> null
        }
    } catch (t: Throwable) {
        fallback
    }
}

// resolveAiFileInput restated by hand: stored name first, then the
// content URI tail, then "document"; a known extension maps to its
// canonical MIME, otherwise the bytes are sniffed for the PDF magic
// and then for plain UTF-8 text (blank, overlong, NUL or replacement
// char inputs are refused).
internal fun resolveAiFileInputOracle175(message: Message, base64: String): PreparedAiFile? {
    val storedName = parsedMetaFileNameOracle175(message.content, message.meta.fileName)
        ?.trim()?.takeIf(String::isNotBlank)
    val cachedName = parsedContentOracle175(message.content)
        .substringAfterLast('/')
        .substringBefore('?')
        .substringBefore('#')
        .takeIf(String::isNotBlank)
    val candidateName = storedName ?: cachedName ?: "document"
    val extension = candidateName.substringAfterLast('.', "").lowercase()
    val canonicalMime = when (extension) {
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        else -> null
    }
    if (canonicalMime != null) return PreparedAiFile(base64, candidateName.take(120), canonicalMime)
    val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
    if (bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
        return PreparedAiFile(base64, "document.pdf", "application/pdf")
    }
    val textContent = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
    if (textContent.isBlank() || textContent.length > 120_000 ||
        '\u0000' in textContent || '\uFFFD' in textContent
    ) return null
    return PreparedAiFile(base64, "document.txt", "text/plain")
}

// Production resolveAiFileInput resolves the AI file input.
internal fun resolveAiFileInputProduction175(message: Message, base64: String): PreparedAiFile? =
    resolveAiFileInput(message, base64)

// ---------- generators ----------

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonAiFile175(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal fun escJsonOrNullAiFile175(s: String?): String =
    s?.let { escJsonAiFile175(it) } ?: "null"

internal fun b64AiFile175(text: String): String =
    Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

internal fun b64BytesAiFile175(bytes: ByteArray): String =
    Base64.getEncoder().encodeToString(bytes)

internal data class AiFileCase175(
    val content: String,
    val metaFileName: String?,
    val base64: String,
)

internal val AI_FILE_NAME_POOL175: List<String?> = listOf(
    null, "", "  ", "report.pdf", "report.PDF", "a.txt", "a.md", "a.markdown",
    "a.csv", "a.json", "a.xml", "data.bin", "noext", "a.", ".pdf",
    "archive.tar.gz", "a".repeat(130) + ".pdf", "  spaced.pdf  ",
    "\u00a0.pdf", "file.PdF", "x.PDF ", "a.b-c", "a.verylongextension",
    "Ü.pdf", "a.İdf", "a.Kpdf", "dir/name.pdf", "a.pdf\u0000",
)

internal val AI_FILE_URI_POOL175: List<String> = listOf(
    "file:///sdcard/report.pdf", "file:///sdcard/report.pdf?x=1#frag",
    "file:///sdcard/note.txt", "content", "a/b/c", "/x/y", "no-slash", "",
    "  ", "file:///a/b", "a.pdf", "dir/", "/.pdf", "a/b.PDF", "x?y#z",
    "a#b/c", "hello", "a\nb", "&#65;.pdf", "a%20b.pdf", "file:///a/b?c=d#e",
    "///", "/", "?", "#", ".", "..", "a..pdf",
)

internal val AI_FILE_META_JSON_POOL175: List<String> = listOf(
    "", "{}", "{\"fileName\":\"a.pdf\"}", "{\"fileName\":\"a.txt\"}",
    "{\"fileName\":null}", "{\"fileName\":123}", "{\"fileName\":true}",
    "{\"fileName\":{}}", "{\"fileName\":[]}", "{\"fileName\":\"  \"}",
    "{\"fileName\":\" spaced.pdf \"}",
    "{\"fileName\":\"a.pdf\",\"fileName\":\"b.txt\"}", "{bad json",
    "{\"fileName\":\"a.pdf\"", "{\"fileName\":\"a.pdf\"} trailing", "   ",
    "{\"other\":1}", "[1,2]", "\"str\"", "123",
    "{\"fileName\":\"\\u0061.pdf\"}", "{\"fileName\":\"a\\ud800b.pdf\"}",
    "{\"fileName\":\"\\ud83d\\ude00.pdf\"}", "{'fileName':'a.pdf'}",
    "{\"fileName\":1e3}", "{\"fileName\":-0}", "{\"fileName\":\"\"}",
    "{\"fileName\":\"a.pdf\"}extra", " \n{\"fileName\" : \"a.pdf\"} ",
    "{\"fileName\":\"a.pdf\",\"x\":[1,{\"y\":2}]}",
)

internal val AI_FILE_BASE64_POOL175: List<String> = listOf(
    "eA==", "JVBERi0xLjQgcmVzdA==", "aGVsbG8gd29ybGQ=", "ICAg", "YQ==",
    "YQ", "YWI", "AR==", "AB==", "AB=C", "AB CD", "AB\nCD", "====",
    "A===", "AB", "ABC=", "ABC", "", "!!!not-base64!!!", "YQ=",
    "YQ==YQ==", "/+9=", "YQ== ", " YQ==", "YWJjZA==",
    "YWJjZA", "YQ==\n", "####", "AB==AB==",
)

internal fun buildAiFileMessage175(c: AiFileCase175): Message = Message(
    id = "m1",
    chatId = "c1",
    senderId = "u1",
    content = c.content,
    type = MessageType.FILE,
    meta = MessageMeta(fileName = c.metaFileName),
)

internal fun randomAiFileCase175(rnd: Random): AiFileCase175 {
    val metaFileName = if (rnd.nextInt(2) == 0) {
        AI_FILE_NAME_POOL175[rnd.nextInt(AI_FILE_NAME_POOL175.size)]
    } else {
        val exts = listOf("pdf", "txt", "bin", "", "PDF", "md", "json", "xml")
        val stem = listOf("a", "report", "x", "a b", "Ü", "a.b")[rnd.nextInt(6)]
        val ext = exts[rnd.nextInt(exts.size)]
        if (ext.isEmpty()) stem else "$stem.$ext"
    }
    var content = AI_FILE_URI_POOL175[rnd.nextInt(AI_FILE_URI_POOL175.size)]
    when (rnd.nextInt(10)) {
        in 0..3 -> {
            val json = AI_FILE_META_JSON_POOL175[rnd.nextInt(AI_FILE_META_JSON_POOL175.size)]
            content += "<meta>$json</meta>"
        }
        4 -> content += "<meta>" +
            AI_FILE_META_JSON_POOL175[rnd.nextInt(AI_FILE_META_JSON_POOL175.size)]
        5 -> content += "<meta>" +
            AI_FILE_META_JSON_POOL175[rnd.nextInt(AI_FILE_META_JSON_POOL175.size)] +
            "</meta>tail<meta>{\"fileName\":\"late.pdf\"}</meta>"
        else -> Unit
    }
    val base64 = when (rnd.nextInt(10)) {
        in 0..5 -> AI_FILE_BASE64_POOL175[rnd.nextInt(AI_FILE_BASE64_POOL175.size)]
        in 6..7 -> {
            val size = rnd.nextInt(0, 40)
            val bytes = ByteArray(size) {
                listOf<Byte>(0x25, 0x50, 0x44, 0x46, 0x2D, 0x20, 0x61, 0xFF.toByte(), 0x00, 0xEF.toByte(), 0xC3.toByte(), 0xA9.toByte())[rnd.nextInt(12)]
            }
            b64BytesAiFile175(bytes)
        }
        else -> b64AiFile175(
            listOf("hello", "x", "   ", "a\u0000b", "a\uFFFDb", "%PDF-9", "Ünïcödé", "🎉")[rnd.nextInt(8)]
        )
    }
    return AiFileCase175(content, metaFileName, base64)
}

internal fun aiFileWireJson175(c: AiFileCase175): String =
    "{\"content\":${escJsonAiFile175(c.content)}," +
        "\"metaFileName\":${escJsonOrNullAiFile175(c.metaFileName)}," +
        "\"base64\":${escJsonAiFile175(c.base64)}}"

internal fun aiFileProjectionJson175(result: PreparedAiFile?): String =
    if (result == null) "null"
    else "{\"file\":${escJsonAiFile175(result.fileName)}," +
        "\"mime\":${escJsonAiFile175(result.mimeType)}," +
        "\"base64\":${escJsonAiFile175(result.base64)}}"

class AiFileInputFuzzTest {

    private fun checkAiFile175(c: AiFileCase175) {
        val message = buildAiFileMessage175(c)
        assertEquals(
            aiFileProjectionJson175(resolveAiFileInputOracle175(message, c.base64)),
            aiFileProjectionJson175(resolveAiFileInputProduction175(message, c.base64)),
            "aiFile($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Stored names win over the URI tail, blank stored names fall
        // back, and the extension table is case-insensitive.
        checkAiFile175(AiFileCase175("file:///sdcard/other.pdf", "stored.txt", b64AiFile175("x")))
        checkAiFile175(AiFileCase175("file:///sdcard/note.txt", "  ", b64AiFile175("x")))
        checkAiFile175(AiFileCase175("file:///sdcard/report.pdf", "report.PDF", b64AiFile175("x")))
        for (name in listOf("a.txt", "a.md", "a.markdown", "a.csv", "a.json", "a.xml")) {
            checkAiFile175(AiFileCase175("file:///sdcard/x", name, b64AiFile175("x")))
        }
        // Long candidate names are cut to 120 units.
        checkAiFile175(AiFileCase175("file:///sdcard/x", "a".repeat(130) + ".pdf", b64AiFile175("x")))
        // The meta tag supplies the name; a broken tag falls back.
        checkAiFile175(AiFileCase175("file:///sdcard/x<meta>{\"fileName\":\"meta.pdf\"}</meta>", null, b64AiFile175("x")))
        checkAiFile175(AiFileCase175("file:///sdcard/x<meta>{bad json</meta>", "fallback.txt", b64AiFile175("x")))
        checkAiFile175(AiFileCase175("file:///sdcard/x<meta>{\"fileName\":123}</meta>", null, b64AiFile175("x")))
        checkAiFile175(AiFileCase175("file:///sdcard/x<meta>{\"fileName\":null}</meta>", "fallback.bin", b64AiFile175("x")))
        // Unknown extensions fall through to sniffing: PDF magic,
        // plain text, and each refusal (blank, overlong, NUL,
        // replacement char, bad base64, malformed UTF-8 bytes).
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", b64AiFile175("%PDF-1.4 rest")))
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", b64AiFile175("hello world")))
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", b64AiFile175("   ")))
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", b64AiFile175("x".repeat(120_001))))
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", b64AiFile175("a\u0000b")))
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", b64AiFile175("a\uFFFDb")))
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", "!!!not-base64!!!"))
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", b64BytesAiFile175(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))))
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", b64AiFile175("a\uD83D\uDE00b")))
        // Unpadded base64 still decodes; a dangling single char does not.
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", "YWI"))
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", "YQ"))
        checkAiFile175(AiFileCase175("file:///sdcard/x", "data.bin", "A"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(175001)
        repeat(4000) { checkAiFile175(randomAiFileCase175(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Every stored name x a few URI shapes x every base64 shape:
        // the name table, the URI tail and the sniffing refusals
        // cross-checked against each other.
        val contents = listOf(
            "file:///sdcard/report.pdf", "file:///sdcard/note.txt", "a/b/c",
            "file:///sdcard/x<meta>{\"fileName\":\"meta.pdf\"}</meta>",
            "file:///sdcard/x<meta>{bad json</meta>",
        )
        for (name in AI_FILE_NAME_POOL175) {
            for (content in contents) {
                for (base64 in AI_FILE_BASE64_POOL175) {
                    checkAiFile175(AiFileCase175(content, name, base64))
                }
            }
        }
    }
}
