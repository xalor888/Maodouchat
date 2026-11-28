package com.maodouchat.server.service

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Moderation decision restated by hand. Ground truth, read from
// AiContentModerationPolicy.kt / AiContentModerationPolicyModels.kt:
//   - extractJsonObject: trim, take first '{' to last '}', lenient-parse
//     that slice; anything else -> allow("unparsed").
//   - string(key): jsonPrimitive content, trimmed, null when missing /
//     blank / JsonNull; numbers and bools read as their literal text.
//     An array or object under verdict/category/reason/explanation
//     throws IllegalArgumentException out of parse (jsonPrimitive only
//     guards null, and the throw is outside extractJsonObject's
//     runCatching), so the oracle reports it as a throw, not ALLOW.
//   - verdict: BLOCK/REJECT/DELETE/HOLD -> BLOCK; REVIEW/FLAG/WARN ->
//     REVIEW; ALLOW/OK/PASS -> ALLOW; anything else -> ALLOW.
//   - category: trim + lowercase, known words fold to ok/spam/scam/
//     porn/hate/violence/other, blank -> other, else first 24 chars.
//   - reason: string("reason") ?: string("explanation"), trim, take 180
//     (UTF-16 units, so an astral char counts two).
//   - ALLOW -> action ALLOW, reason "", needsReview false; REVIEW ->
//     WARN_MOD + default reason when blank; BLOCK -> AUTO_HOLD +
//     default reason when blank. matchedPreview is
//     "verdict/category: reason" capped at 280 units.
internal sealed interface ModNode187
internal data class ModStr187(val value: String) : ModNode187
internal data class ModLit187(val text: String) : ModNode187
internal data object ModNull187 : ModNode187
internal data class ModArr187(val items: List<ModNode187>) : ModNode187
internal data class ModObj187(val entries: Map<String, ModNode187>) : ModNode187

internal sealed interface ModerationOutcome187 {
    data class Ok(val decision: Decision, val preview: String) : ModerationOutcome187
    data object Throws : ModerationOutcome187
}

internal class LenientParser187(private val s: String) {
    private var pos = 0

    private fun isWs(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun isLitStop(c: Char): Boolean =
        c.code <= 0x20 || c == ',' || c == ']' || c == '}' || c == ':' ||
            c == '"' || c == '{' || c == '[' || c == '\\'

    // Bare keys stop at whitespace and structural chars, but keep other
    // C0 controls (kotlinx reads a key until ':' / ws / delimiter).
    private fun isKeyStop(c: Char): Boolean =
        isWs(c) || c == ',' || c == ']' || c == '}' || c == ':' ||
            c == '"' || c == '{' || c == '[' || c == '\\'

    private fun skipWs() {
        while (pos < s.length && isWs(s[pos])) pos++
    }

    fun parseTop(): ModNode187 {
        val v = parseValue()
        skipWs()
        if (pos != s.length) error("trailing")
        return v
    }

    private fun parseValue(): ModNode187 {
        skipWs()
        if (pos >= s.length) error("eof")
        return when (s[pos]) {
            '{' -> parseObj()
            '[' -> parseArr()
            '"' -> ModStr187(parseString())
            else -> parseLiteral()
        }
    }

    private fun parseString(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= s.length) error("unterminated")
            val c = s[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                if (pos >= s.length) error("bad escape")
                when (val e = s[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\u0008')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > s.length) error("bad \\u")
                        var v = 0
                        for (k in 0 until 4) {
                            val h = s[pos + k]
                            val d = when (h) {
                                in '0'..'9' -> h - '0'
                                in 'a'..'f' -> h - 'a' + 10
                                in 'A'..'F' -> h - 'A' + 10
                                else -> error("bad hex")
                            }
                            v = v * 16 + d
                        }
                        pos += 4
                        sb.append(v.toChar())
                    }
                    else -> error("bad escape")
                }
            } else {
                // Lenient mode keeps raw control chars inside strings.
                sb.append(c)
            }
        }
    }

    private fun parseLiteral(): ModNode187 {
        val start = pos
        while (pos < s.length && !isLitStop(s[pos])) pos++
        if (pos == start) error("empty literal")
        val tok = s.substring(start, pos)
        if (tok == "null") return ModNull187
        return ModLit187(tok)
    }

    private fun parseObj(): ModNode187 {
        pos++ // {
        val entries = LinkedHashMap<String, ModNode187>()
        skipWs()
        if (pos < s.length && s[pos] == '}') {
            pos++
            return ModObj187(entries)
        }
        while (true) {
            skipWs()
            if (pos >= s.length) error("eof in object")
            val key = if (s[pos] == '"') parseString() else {
                val start = pos
                while (pos < s.length && !isKeyStop(s[pos])) pos++
                if (pos == start) error("empty key")
                s.substring(start, pos)
            }
            skipWs()
            if (pos >= s.length || s[pos] != ':') error("missing colon")
            pos++
            entries[key] = parseValue()
            skipWs()
            if (pos >= s.length) error("eof in object")
            if (s[pos] == ',') {
                pos++
                continue
            }
            if (s[pos] == '}') {
                pos++
                return ModObj187(entries)
            }
            error("expected , or }")
        }
    }

    private fun parseArr(): ModNode187 {
        pos++ // [
        val items = ArrayList<ModNode187>()
        skipWs()
        if (pos < s.length && s[pos] == ']') {
            pos++
            return ModArr187(items)
        }
        while (true) {
            items.add(parseValue())
            skipWs()
            if (pos >= s.length) error("eof in array")
            if (s[pos] == ',') {
                pos++
                continue
            }
            if (s[pos] == ']') {
                pos++
                return ModArr187(items)
            }
            error("expected , or ]")
        }
    }
}

internal fun extractJsonObject187(raw: String): ModObj187? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val start = trimmed.indexOf('{')
    val end = trimmed.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val parsed = runCatching { LenientParser187(trimmed.substring(start, end + 1)).parseTop() }.getOrNull()
    return parsed as? ModObj187
}

internal fun moderationString187(obj: ModObj187, key: String): String? {
    val v = obj.entries[key] ?: return null
    if (v is ModObj187 || v is ModArr187) throw IllegalArgumentException("not a primitive")
    val content: String? = when (v) {
        is ModStr187 -> v.value
        is ModLit187 -> v.text
        is ModNull187 -> null
        else -> null
    }
    return content?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun normalizeCategory187(raw: String?): String {
    val value = raw?.trim()?.lowercase().orEmpty()
    return when (value) {
        "ok", "none", "safe" -> "ok"
        "spam", "ad", "ads", "promo" -> "spam"
        "scam", "phish", "phishing", "fraud" -> "scam"
        "porn", "sexual", "nsfw" -> "porn"
        "hate", "abuse" -> "hate"
        "violence", "threat" -> "violence"
        "other", "misc" -> "other"
        else -> if (value.isBlank()) "other" else value.take(24)
    }
}

internal fun allow187(category: String): Decision =
    Decision(Verdict.ALLOW, "ALLOW", category, "", false)

internal fun moderationPreview187(d: Decision): String =
    "${d.verdict.name}/${d.category}: ${d.reason}".take(280)

internal fun moderationDecisionOracle187(raw: String): ModerationOutcome187 {
    val obj = extractJsonObject187(raw) ?: return ModerationOutcome187.Ok(
        allow187("unparsed"),
        moderationPreview187(allow187("unparsed"))
    )
    val verdict = when (moderationString187(obj, "verdict")?.uppercase()) {
        "BLOCK", "REJECT", "DELETE", "HOLD" -> Verdict.BLOCK
        "REVIEW", "FLAG", "WARN" -> Verdict.REVIEW
        "ALLOW", "OK", "PASS" -> Verdict.ALLOW
        else -> Verdict.ALLOW
    }
    val category = normalizeCategory187(moderationString187(obj, "category"))
    val reason = (moderationString187(obj, "reason")
        ?: moderationString187(obj, "explanation").orEmpty())
        .trim().take(180)
    val decision = when (verdict) {
        Verdict.ALLOW -> allow187(category.ifBlank { "ok" })
        Verdict.REVIEW -> Decision(
            Verdict.REVIEW, "WARN_MOD", category.ifBlank { "other" },
            reason.ifBlank { "模型标记需人工复核" }, true
        )
        Verdict.BLOCK -> Decision(
            Verdict.BLOCK, "AUTO_HOLD", category.ifBlank { "other" },
            reason.ifBlank { "模型判定不宜公开发布" }, true
        )
    }
    return ModerationOutcome187.Ok(
        decision,
        moderationPreview187(decision)
    )
}

internal fun escJsonModeration187(s: String): String {
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
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
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

internal fun moderationResultWireJson187(o: ModerationOutcome187): String = when (o) {
    is ModerationOutcome187.Ok -> "{\"verdict\":${escJsonModeration187(o.decision.verdict.name)}," +
        "\"action\":${escJsonModeration187(o.decision.action)}," +
        "\"category\":${escJsonModeration187(o.decision.category)}," +
        "\"reason\":${escJsonModeration187(o.decision.reason)}," +
        "\"needsReview\":${o.decision.needsReview}," +
        "\"preview\":${escJsonModeration187(o.preview)}}"
    is ModerationOutcome187.Throws -> "THROW:IllegalArgumentException"
}

// ---------- production ----------

internal fun moderationDecisionProduction187(raw: String): ModerationOutcome187 {
    return try {
        val d = AiContentModerationPolicy.parse(raw)
        ModerationOutcome187.Ok(d, AiContentModerationPolicy.matchedPreview(d))
    } catch (e: IllegalArgumentException) {
        ModerationOutcome187.Throws
    }
}

// ---------- generators ----------

internal val MODERATION_VERDICTS187 = listOf(
    "ALLOW", "REVIEW", "BLOCK", "allow", "review", "block", "Block", "bLoCk",
    "REJECT", "DELETE", "HOLD", "FLAG", "WARN", "OK", "PASS", "MAYBE", "",
    " block ", "BLOCK ", "BLOCK\n", "É", "é", "ALLOWX", "BLOCKS"
)

internal val MODERATION_CATEGORIES187 = listOf(
    "ok", "none", "safe", "spam", "ad", "ads", "promo", "scam", "phish",
    "phishing", "fraud", "porn", "sexual", "nsfw", "hate", "abuse",
    "violence", "threat", "other", "misc", "weird", "SPAM", "Spam", "sPaM",
    "  spam  ", "", "   ", "aVeryLongCategoryNameExceedingTwentyFourChars",
    "1234567890123456789012345", "é", "ÉÉ", "a", "ab"
)

internal val MODERATION_REASONS187 = listOf(
    "ads", "phishing link", "x", "y", "", "   ", "line1\nline2", "tab\there",
    "café", "\uD83D\uDE00", "中文", "x".repeat(200), "y".repeat(179),
    "z".repeat(180), "w".repeat(181), "\uD83D\uDE00".repeat(100),
    "\uD83D\uDE00".repeat(90), "\u4e2d".repeat(181), "a\"b", "a\\b", "a/b",
    "a\u0001b", "a\u001fb", "  padded  ", "\u00a0", "\u2003"
)

internal val MODERATION_SCALARS187 = listOf(
    "\"v\"", "123", "1.5", "1e2", "true", "false", "null", "007", "+7", ".7",
    "NaN", "Infinity", "-0", "0x1F", "1_000", "01", "-5", "123abc", "hello-world",
    "hello/world", "BLOCK!", "'BLOCK'", "'spam'"
)

internal fun moderationValue187(rnd: Random, pool: List<String>): String {
    // Quote the value, escaping every control char: a raw control byte
    // inside a quoted string is an unprobed corner of lenient mode, so
    // the generator only ever emits it escaped (the pinned leg covers
    // the raw form explicitly).
    fun quote(v: String): String {
        val sb = StringBuilder("\"")
        for (ch in v) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u0008' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code))
                    else sb.append(ch)
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }
    return when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4 -> quote(pool[rnd.nextInt(pool.size)])
        5 -> pool[rnd.nextInt(pool.size)] // bare literal
        6 -> MODERATION_SCALARS187[rnd.nextInt(MODERATION_SCALARS187.size)]
        7 -> "[1, 2]"
        8 -> "{\"a\":1}"
        else -> quote(pool[rnd.nextInt(pool.size)])
    }
}

internal fun randomModerationRaw187(rnd: Random): String {
    val r = rnd.nextInt(10)
    if (r < 6) {
        val parts = ArrayList<String>()
        if (rnd.nextInt(10) < 9) {
            parts.add("\"verdict\":" + moderationValue187(rnd, MODERATION_VERDICTS187))
        }
        if (rnd.nextInt(10) < 7) {
            parts.add("\"category\":" + moderationValue187(rnd, MODERATION_CATEGORIES187))
        }
        if (rnd.nextInt(10) < 7) {
            parts.add("\"reason\":" + moderationValue187(rnd, MODERATION_REASONS187))
        }
        if (rnd.nextInt(10) < 3) {
            parts.add("\"explanation\":" + moderationValue187(rnd, MODERATION_REASONS187))
        }
        if (rnd.nextInt(10) < 2) parts.add("\"nested\":{\"a\":1}")
        if (rnd.nextInt(10) < 1 && parts.isNotEmpty()) parts.add(parts[rnd.nextInt(parts.size)])
        parts.shuffle(rnd)
        val sep = if (rnd.nextInt(10) < 8) "," else ", "
        var body = "{" + parts.joinToString(sep) + "}"
        if (rnd.nextInt(10) < 1) body = body.dropLast(1) + ",}"
        if (rnd.nextInt(10) < 1) body = "{" + body
        if (rnd.nextInt(10) < 2) body = "prefix " + body + " suffix"
        if (rnd.nextInt(10) < 1) body = body.replace("\"verdict\"", "verdict")
        if (rnd.nextInt(10) < 1) body = body.replace("\"", "'")
        return body
    }
    if (r < 8) {
        // Bare-key / single-quote / comment shapes.
        return listOf(
            "{verdict:BLOCK}",
            "{verdict:BLOCK,category:spam}",
            "{'verdict':'BLOCK'}",
            "{verdict:'BLOCK', category:'spam'}",
            "// note\n{\"verdict\":\"BLOCK\"}",
            "{\"verdict\":\"BLOCK\"} // trailing",
            "{\"verdict\":\"BLOCK\", /* inline */ \"category\":\"spam\"}",
            "{\"a\":{\"verdict\":\"BLOCK\"}}",
            "{\"verdict\":\"BLOCK\"} {\"verdict\":\"ALLOW\"}"
        )[rnd.nextInt(9)]
    }
    // Near-miss mutations of a valid object.
    val base = "{\"verdict\":\"BLOCK\",\"category\":\"spam\",\"reason\":\"ads\"}"
    return when (rnd.nextInt(5)) {
        0 -> base.substring(0, rnd.nextInt(base.length))
        1 -> base + "x".repeat(rnd.nextInt(5))
        2 -> base.replace("BLOCK", listOf("BLO CK", "BLOCK!", "BLOCK:1", "BLOCK}").random(rnd))
        3 -> ""
        else -> "not json at all"
    }
}

internal fun randomModerationCase187(rnd: Random): String = randomModerationRaw187(rnd)

internal fun moderationCaseWireJson187(raw: String): String = escJsonModeration187(raw)

internal val MODERATION_PINNED187 = listOf(
    "", " ", "{}", "{\"verdict\":\"ALLOW\"}", "{\"verdict\":\"BLOCK\"}",
    "{\"verdict\":\"REVIEW\"}", "{\"verdict\":\"block\"}",
    "{\"verdict\":\"REJECT\"}", "{\"verdict\":\"DELETE\"}",
    "{\"verdict\":\"HOLD\"}", "{\"verdict\":\"FLAG\"}", "{\"verdict\":\"WARN\"}",
    "{\"verdict\":\"OK\"}", "{\"verdict\":\"PASS\"}", "{\"verdict\":\"MAYBE\"}",
    "{\"verdict\":123}", "{\"verdict\":true}", "{\"verdict\":null}",
    "{\"verdict\":[\"BLOCK\"]}", "{\"verdict\":{\"x\":1}}",
    "{\"category\":\"spam\"}", "{\"category\":\"SPAM\"}",
    "{\"verdict\":\"BLOCK\",\"category\":\"SPAM\"}",
    "{\"verdict\":\"BLOCK\",\"category\":\"weird-category-name-that-is-long\"}",
    "{\"verdict\":\"BLOCK\",\"category\":\"\"}",
    "{\"verdict\":\"BLOCK\",\"category\":123}",
    "{\"verdict\":\"BLOCK\",\"category\":true}",
    "{\"verdict\":\"BLOCK\",\"category\":null}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"x\"}",
    "{\"verdict\":\"BLOCK\",\"explanation\":\"y\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"r\",\"explanation\":\"e\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":123}",
    "{\"verdict\":\"BLOCK\",\"reason\":[1]}",
    "{\"verdict\":\"BLOCK\",\"reason\":{\"a\":1}}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"" + "x".repeat(200) + "\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"" + "y".repeat(179) + "\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"" + "z".repeat(180) + "\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"" + "w".repeat(181) + "\"}",
    "prefix {\"verdict\":\"BLOCK\"} suffix",
    "{\"verdict\":\"BLOCK\",}", "{\"verdict\":\"BLOCK\", \"category\":\"spam\",}",
    "{'verdict':'BLOCK'}", "{verdict:\"BLOCK\"}", "{verdict:BLOCK}",
    "{\"verdict\":BLOCK}", "{verdict:'BLOCK', category:'spam'}",
    "{\"verdict\":\"BLOCK\" \"category\":\"spam\"}",
    "{\"verdict\":\"BLOCK\";\"category\":\"spam\"}",
    "{\"verdict\":\"BLOCK\",, \"category\":\"spam\"}",
    "{\"verdict\"::\"BLOCK\"}",
    "{\"verdict\":\"BLOCK\"} {\"verdict\":\"ALLOW\"}",
    "{\"a\":{\"verdict\":\"BLOCK\"}}",
    "{\"verdict\":\"BLOCK\",\"nested\":{\"a\":1}}",
    "[1,2,3]", "\"str\"", "123", "true", "null",
    "{\"verdict\":\"BLOCK\"} garbage {", "{ {\"verdict\":\"BLOCK\"} }",
    "{\"verdict\" :\"BLOCK\" , \"category\" : \"spam\" }",
    "{\n\"verdict\" : \"BLOCK\",\n\"category\" : \"spam\"\n}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"line1\\nline2\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"caf\\u00e9\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\\uD83D\\uDE00\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\\uD83D\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\\uDE00\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\\x\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\\u12\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\\uZZZZ\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"a\\\"b\"}",
    "{\"VERDICT\":\"BLOCK\"}", "{\"Verdict\":\"BLOCK\"}",
    "{\"verdict\":\"BLOCK\",\"VERDICT\":\"ALLOW\"}",
    "{\"verdict\":\"ALLOW\",\"verdict\":\"BLOCK\"}",
    "{\"verdict\":\"BLOCK\",\"verdict\":\"ALLOW\"}",
    "{\"verdict\":\"BLOCK\",\"category\":\"spam\",\"category\":\"scam\"}",
    "{\"verdict\":\"bLoCk\"}", "{\"verdict\":\" block \"}",
    "{\"verdict\":\"BLOCK \"}", "{\"verdict\":\"BLOCK\\n\"}",
    "{\"verdict\":\"REVIEW\",\"category\":\"porn\",\"reason\":\"\"}",
    "{\"verdict\":\"REVIEW\",\"category\":\"porn\"}",
    "{\"verdict\":\"ALLOW\",\"category\":\"weird\"}",
    "{\"verdict\":\"ALLOW\",\"category\":\"\"}",
    "{\"verdict\":\"ALLOW\",\"reason\":\"ignored?\"}",
    "x{\"verdict\":\"BLOCK\"}y", "{{\"verdict\":\"BLOCK\"}}",
    "{\"verdict\":\"BLOCK\"}}", "{\"verdict\":\"BLOCK\"",
    "{\"verdict\":}", "{\"verdict\"}", "{\"verdict\":\"BLOCK\",\"reason\":}",
    "{\"verdict\":\"BLOCK\",\"reason\":123456789012345678901234567890}",
    "{\"verdict\":\"BLOCK\",\"reason\":1.5}",
    "{\"verdict\":\"BLOCK\",\"reason\":1e2}",
    "{\"verdict\":\"BLOCK\",\"reason\":true}",
    "{\"verdict\":\"BLOCK\",\"category\":\"aVeryLongCategoryNameExceedingTwentyFourChars\"}",
    "{\"verdict\":\"BLOCK\",\"category\":\"123456789012345678901234\"}",
    "{\"verdict\":\"BLOCK\",\"category\":\"1234567890123456789012345\"}",
    "// comment\n{\"verdict\":\"BLOCK\"}", "/* c */ {\"verdict\":\"BLOCK\"}",
    "{\"verdict\":\"BLOCK\"} // trailing",
    "{\"verdict\":\"BLOCK\", /* inline */ \"category\":\"spam\"}",
    "{\"verdict\":\"BLOCK\"\n}", "{\"verdict\" :\"BLOCK\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\\ud83d\\ude00 ok\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\\ud800\\udc00\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\\udc00\\ud800\"}",
    "{\"verdict\":\"\u00c9\"}", "{\"verdict\":\"\u00e9\"}",
    "{\"verdict\":\"BLOCK\",\"category\":\"\u00e9\"}",
    "{\"verdict\":\"BLOCK\",\"category\":\"\u00c9\u00c9\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"" + "\u4e2d".repeat(100) + "\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"" + "\uD83D\uDE00".repeat(100) + "\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"" + "\uD83D\uDE00".repeat(90) + "\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"" + "\uD83D\uDE00".repeat(89) + "\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"" + "\uD83D\uDE00".repeat(91) + "\"}",
    "{verdict:BLO CK}", "{verdict:BLOCK!}", "{verdict:hello-world}",
    "{verdict:hello/world}", "{verdict:123abc}", "{verdict:12.5}",
    "{verdict:-5}", "{verdict:.5}", "{verdict:0x10}", "{verdict:1e5}",
    "{verdict:true}", "{verdict:false}", "{verdict:null}", "{verdict:NaN}",
    "{verdict:Infinity}", "{verdict:+5}", "{verdict:01}", "{verdict:12:30}",
    "{verdict:hello:world}", "{my-key:\"BLOCK\"}", "{my_key:\"BLOCK\"}",
    "{my.key:\"BLOCK\"}", "{verdict :\"BLOCK\"}",
    "{verdict:\"BL\nOCK\"}", "{verdict:\"BL\tOCK\"}",
    "{\"verdict\":\"BL\nOCK\"}", "{\"verdict\":\"BL\tOCK\"}",
    "{verdict:BLOCK,category:spam}", "{verdict:BLOCK category:spam}",
    "{\"verdict\":\"BLOCK\",\"category\":spam}",
    "{\"verdict\":\"BLOCK\",\"category\":spam!}",
    "{\"verdict\":\"BLOCK\",\"category\":1.5}",
    "{\"verdict\":\"BLOCK\",\"category\":NaN}",
    "{\"verdict\":\"BLOCK\",\"category\":Infinity}",
    "{\"verdict\":\"BLOCK\",\"reason\":NaN}",
    "{\"verdict\":\"BLOCK\",\"reason\":Infinity}",
    "{\"verdict\":\"BLOCK\",\"reason\":-0}",
    "{\"verdict\":\"BLOCK\",\"reason\":0.5}",
    "{\"verdict\":\"BLOCK\",\"reason\":007}",
    "{\"verdict\":\"BLOCK\",\"reason\":+7}",
    "{\"verdict\":\"BLOCK\",\"reason\":.7}",
    "{\"verdict\":\"BLOCK\",\"reason\":1_000}",
    "{\"verdict\":\"BLOCK\",\"reason\":0x1F}",
    "{verdict:\"BLOCK\",reason:hello world}",
    "{verdict:\"BLOCK\",reason:hello,world}",
    "{verdict:\"BLOCK\",reason:hello}",
    "{verdict:\"BLOCK\",reason:hello }",
    "{\"verdict\":\"BLOCK\",\"reason\":\"caf\u00e9\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\u4e2d\u6587\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\uD83D\uDE00\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\" \"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\u00a0\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\u2003\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\u000b\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"\u000c\"}",
    // Raw control bytes inside a quoted string (not escapes).
    "{\"verdict\":\"BLOCK\",\"reason\":\"a\u0001b\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"a\u007fb\"}",
    "{\"verdict\":\"BLOCK\",\"reason\":\"a\u000bb\"}",
    "{\"verdict\":\"BLOCK\",\"category\":\"\u00a0spam\u00a0\"}",
    "{\"verdict\":\"\u00a0BLOCK\u00a0\"}",
    "{\"verdict\":\"\u2003BLOCK\u2003\"}"
)

class AiContentModerationPolicyFuzzTest {

    private fun checkModeration187(raw: String) {
        val oracle = runCatching { moderationDecisionOracle187(raw) }.getOrElse { ModerationOutcome187.Throws }
        val production = moderationDecisionProduction187(raw)
        assertEquals(
            moderationResultWireJson187(oracle),
            moderationResultWireJson187(production),
            "moderation($raw)"
        )
    }

    @Test
    fun pinnedAiContentModeration() {
        for (raw in MODERATION_PINNED187) checkModeration187(raw)
    }

    @Test
    fun randomAiContentModerationMatchProduction() {
        val rnd = Random(187002)
        repeat(2500) { checkModeration187(randomModerationCase187(rnd)) }
    }

    @Test
    fun sweepAiContentModerationBoundaries() {
        // Every verdict word x every category word, quoted and bare.
        for (v in MODERATION_VERDICTS187) {
            for (c in MODERATION_CATEGORIES187) {
                checkModeration187("{\"verdict\":\"$v\",\"category\":\"$c\"}")
                checkModeration187("{verdict:$v,category:$c}")
            }
        }
        // Reason length edges around take(180), in chars and in UTF-16 units.
        for (n in listOf(0, 1, 179, 180, 181, 200)) {
            checkModeration187("{\"verdict\":\"BLOCK\",\"reason\":\"" + "r".repeat(n) + "\"}")
            checkModeration187("{\"verdict\":\"BLOCK\",\"reason\":\"" + "\uD83D\uDE00".repeat(n / 2) + "\"}")
        }
        // Whitespace-only and trim-edge reasons.
        for (r in listOf("", " ", "  ", "\t", "\n", "\u00a0", "\u2003", " x ", "\u00a0x\u00a0")) {
            checkModeration187("{\"verdict\":\"BLOCK\",\"reason\":\"$r\"}")
        }
        // Explanation fallback matrix.
        for (reason in listOf<String?>(null, "", "  ", "r")) {
            for (expl in listOf<String?>(null, "", "  ", "e")) {
                val parts = ArrayList<String>()
                parts.add("\"verdict\":\"BLOCK\"")
                if (reason != null) parts.add("\"reason\":\"$reason\"")
                if (expl != null) parts.add("\"explanation\":\"$expl\"")
                checkModeration187("{" + parts.joinToString(",") + "}")
            }
        }
    }
}
