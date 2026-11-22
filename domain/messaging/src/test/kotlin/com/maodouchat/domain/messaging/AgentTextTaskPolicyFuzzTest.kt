// Fuzz / random-oracle tests for AgentTextTaskPolicy (checklist line 931,
// 71st block). Test-only, zero production changes. The oracle is
// hand-written: trim / blank check over the probed 28-unit Kotlin
// whitespace set, unit-by-unit equality, hand-written take (UTF-16
// units), per-code-point simple lowercase, hand-written contains,
// explicit membership loops. transcript joins sender/text lines with newlines; rewriteBaseInstruction maps 9 modes after trim+lowercase (translate interpolates the language, blank falls back to 中文); rewriteInstruction appends the style verbatim when non-blank; suggestInstruction whitelists 9 tones and clamps count to 1..4; summarizeInstruction maps 5 styles; groupAssistantInstruction maps 5 modes and trims + takes(700) the query.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTextTaskPolicyFuzzTest {

// ---------- shared oracle (all three clusters) ----------

// The probed Kotlin blank set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as blank, U+0085 NEL / U+200B / U+FEFF do not.
// Hand-written, never calls Char.isWhitespace / String.isBlank.
private val BLANK_UNITS_ORACLE = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

private fun isBlankOracle(s: String): Boolean {
    for (c in s) {
        if (c.code !in BLANK_UNITS_ORACLE) return false
    }
    return true
}

// Hand-written trim: strip leading/trailing UTF-16 units in the
// probed 28-unit Kotlin whitespace set. Never trim().
private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

// Unit-by-unit equality. Never == on strings.
private fun equalsOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        if (a[i] != b[i]) return false
    }
    return true
}

// Per-code-point simple case mapping. Agrees with String.lowercase()
// (full mapping) on the case-safe alphabet; never calls lowercase().
// Diverges on purpose outside the alphabet: U+0130 İ (full mapping
// expands to i + U+0307), word-final sigma, U+0587 -- those are
// pinned with probe-verified literals, never generated.
private fun lowercaseOracle(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        sb.append(Character.toChars(Character.toLowerCase(cp)))
        i += Character.charCount(cp)
    }
    return sb.toString()
}

// Hand-written substring search over UTF-16 units. Never contains().
private fun containsOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (s[i + j] != needle[j]) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

// Case-insensitive contains: per-char fold, exactly as
// regionMatches(ignoreCase = true) does it -- equal, else equal after
// toUpperCase, else equal after toLowerCase. Exact for every input;
// no alphabet restriction applies to this function.
private fun charsEqualIgnoreCaseOracle(a: Char, b: Char): Boolean {
    if (a == b) return true
    if (Character.toUpperCase(a) == Character.toUpperCase(b)) return true
    return Character.toLowerCase(a) == Character.toLowerCase(b)
}

private fun containsIgnoreCaseOracle(s: String, needle: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > s.length) return false
    for (i in 0..s.length - needle.length) {
        var ok = true
        for (j in needle.indices) {
            if (!charsEqualIgnoreCaseOracle(s[i + j], needle[j])) { ok = false; break }
        }
        if (ok) return true
    }
    return false
}

private fun replaceCharOracle(s: String, from: Char, to: Char): String {
    val chars = s.toCharArray()
    for (i in chars.indices) {
        if (chars[i] == from) chars[i] = to
    }
    return String(chars)
}

// Lexicographic order over UTF-16 units, as String.compareTo.
private fun compareOracle(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val d = a[i].code - b[i].code
        if (d != 0) return d
    }
    return a.length - b.length
}

// Hand-written take: first n UTF-16 units. Never take().
private fun takeOracle(s: String, n: Int): String {
    if (n <= 0) return ""
    if (n >= s.length) return s
    return s.substring(0, n)
}

private fun coerceAtLeastOracle(v: Int, lo: Int): Int = if (v < lo) lo else v

// ---------- shared generators ----------

// Every code point here maps 1:1 under BOTH the per-code-point simple
// mapping (Character.toLowerCase, what the oracle uses) and the full
// string mapping (String.lowercase(), what production uses), and folds
// the same under contains(ignoreCase = true). Excluded on purpose:
// Greek (word-final sigma), Armenian, ligatures U+FB00-FB17, U+0130
// (İ), U+00DF (ß, conservative), U+0131 (ı: upper folds to I,
// simple lowercase keeps ı), U+0149, U+017F (ſ: ignoreCase folds
// it to s, simple lowercase does not), U+01F0, U+1E9B (ṡ: upper folds
// to Ṡ, simple lowercase does not), Cherokee, Georgian --
// all special-casing code points. Same ranges as block 66, minus
// ſ / ı / U+1E9B.
private val CASE_SAFE_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00C0 to 0x00DE, 0x00E0 to 0x00FE,
    0x0100 to 0x012F, 0x0132 to 0x0148, 0x014A to 0x017E, 0x0180 to 0x01EF,
    0x0300 to 0x036F,
    0x1E00 to 0x1E9A, 0x1E9C to 0x1EFF,
    0x2000 to 0x206F,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF,
    0xFF21 to 0xFF3A, 0xFF41 to 0xFF5A,
    0x10400 to 0x1044F
)

// Astral emoji: no case at all, lowercase is identity on both sides.
private val EMOJI_RANGES = arrayOf(
    0x1F300 to 0x1F5FF, 0x1F600 to 0x1F64F, 0x1F680 to 0x1F6FF, 0x1F900 to 0x1F9FF
)

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private fun randomCaseSafeCodePoint(rnd: Random): Int {
    val (lo, hi) = CASE_SAFE_RANGES[rnd.nextInt(CASE_SAFE_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomEmojiCodePoint(rnd: Random): Int {
    val (lo, hi) = EMOJI_RANGES[rnd.nextInt(EMOJI_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomString(rnd: Random, maxLen: Int = 40): String {
    val len = when (rnd.nextInt(8)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 8; 5 -> 16; 6 -> 32
        else -> rnd.nextInt(0, maxLen + 1)
    }
    val sb = StringBuilder()
    var n = 0
    while (n < len) {
        when (rnd.nextInt(10)) {
            0 -> { sb.append(' '); n++ }
            1 -> { sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)]); n++ }
            2 -> { sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++ }
            3 -> { sb.append(Character.toChars(randomEmojiCodePoint(rnd))); n += 2 }
            else -> {
                val cp = randomCaseSafeCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// Short tokens for ids / pack ids / sticker names: pool words (so the
// interesting collisions actually happen) mixed with random strings.
private val TOKEN_POOL = arrayOf(
    "a", "b", "c", "A", "B", "x", "y", "z", "ab", "AB", "a_b", "a-b",
    "mood", "gesture", "party", "cat", "CAT", "Cat", "happy", "HAPPY",
    "ok", "fire", "pet", "love", "hi", "no", "win", "star", "", " ",
    "  ", "😀", "🎉", "🔥", "👍", "🥳", "a b", "funny_cat", "funny-cat"
)

private fun randomToken(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 6) else TOKEN_POOL[rnd.nextInt(TOKEN_POOL.size)]

private fun randomWord(rnd: Random): String {
    return when (rnd.nextInt(5)) {
        0 -> "cat"
        1 -> "CAT"
        2 -> "funny cat"
        3 -> "happy"
        else -> randomString(rnd, 12)
    }
}

// ---------- AgentTextTaskPolicy oracle ----------

private fun textTranscriptLineOracle(sender: String, text: String): String =
    sender + ": " + text

private fun textTranscriptOracle(messages: List<AiContextMessage>): String {
    val sb = StringBuilder()
    for ((index, m) in messages.withIndex()) {
        if (index > 0) sb.append('\n')
        sb.append(textTranscriptLineOracle(m.sender, m.text))
    }
    return sb.toString()
}

private fun textRewriteBaseOracle(mode: String, targetLanguage: String?): String {
    val m = lowercaseOracle(trimOracle(mode))
    val safe = if (equalsOracle(m, "shorten")) "缩短，保留原意"
    else if (equalsOracle(m, "formal")) "更正式礼貌"
    else if (equalsOracle(m, "gentle")) "更温和"
    else if (equalsOracle(m, "casual")) "更口语"
    else if (equalsOracle(m, "professional")) "更专业商务"
    else if (equalsOracle(m, "expand")) "稍加展开，不编造事实"
    else if (equalsOracle(m, "bullet")) "改成简洁条目"
    else if (equalsOracle(m, "clarify")) "更清楚，不改变立场"
    else if (equalsOracle(m, "translate")) {
        val lang = if (targetLanguage == null) "" else trimOracle(targetLanguage)
        "翻译成 " + (if (isBlankOracle(lang)) "中文" else lang) + "，只输出译文"
    } else "润色，保持原意和语气"
    return "改写下面的草稿：" + safe + "。只输出改写结果，不要解释。"
}

private fun textRewriteOracle(mode: String, targetLanguage: String?, style: String?): String {
    val base = textRewriteBaseOracle(mode, targetLanguage)
    if (style == null || isBlankOracle(style)) return base
    return base + " " + style
}

private fun textTranslateOracle(targetLanguage: String): String {
    val t = trimOracle(targetLanguage)
    return "把下面文本翻译成 " + (if (isBlankOracle(t)) "中文" else t) + "。只输出译文。"
}

private val SUGGEST_TONES_ORACLE = arrayOf(
    "natural", "friendly", "formal", "concise", "warm",
    "humorous", "direct", "empathetic", "encouraging"
)

private fun textSuggestOracle(tone: String, count: Int): String {
    val t = lowercaseOracle(trimOracle(tone))
    var safe = "friendly"
    for (c in SUGGEST_TONES_ORACLE) {
        if (equalsOracle(t, c)) { safe = t; break }
    }
    val n = if (count < 1) 1 else if (count > 4) 4 else count
    return "根据对话写 " + n + " 条可直接发送的回复，语气 " + safe + "。每条一行，不要编号以外的解释。"
}

private fun textSummarizeOracle(style: String): String {
    val s = lowercaseOracle(trimOracle(style))
    val safe = if (equalsOracle(s, "detailed")) "详细叙述"
    else if (equalsOracle(s, "decisions")) "只列已做出的决定"
    else if (equalsOracle(s, "tasks")) "只列待办，每行一条"
    else if (equalsOracle(s, "timeline")) "按时间顺序列要点"
    else if (equalsOracle(s, "risks")) "只列风险和阻塞"
    else "简要概括"
    return "总结这些本机已解密消息。风格：" + safe + "。不要声称你已在聊天里执行了任何操作。"
}

private val GROUP_MODES_ORACLE = arrayOf("summary", "decisions", "tasks", "timeline", "risks")

private fun textGroupOracle(mode: String, query: String): String {
    val m = lowercaseOracle(trimOracle(mode))
    var safe = "answer"
    for (c in GROUP_MODES_ORACLE) {
        if (equalsOracle(m, c)) { safe = m; break }
    }
    return "用本机已解密的群聊上下文回答。模式=" + safe + "。用户问题：" + takeOracle(trimOracle(query), 700)
}

private val TEXT_MODES = arrayOf(
    "shorten", "formal", "gentle", "casual", "professional",
    "expand", "bullet", "clarify", "translate",
    "SHORTEN", "Formal", " Translate ", "TRANSLATE",
    "robotic", "", "  ", "polish"
)

private val TEXT_TONES = arrayOf(
    "natural", "friendly", "formal", "concise", "warm",
    "humorous", "direct", "empathetic", "encouraging",
    "WARM", " Friendly ", "robotic", "", "  "
)

private val TEXT_STYLES = arrayOf(
    "detailed", "decisions", "tasks", "timeline", "risks",
    "DETAILED", " Decisions ", "unknown", "", "  "
)

private val TEXT_GROUP_MODES = arrayOf(
    "summary", "decisions", "tasks", "timeline", "risks",
    "SUMMARY", " answer ", "chat", "", "  "
)

private val TEXT_LANGUAGES = arrayOf(
    "英语", " 英语 ", "English", "日本語", "  ", "", "français"
)

private fun genTextMode(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 12) else TEXT_MODES[rnd.nextInt(TEXT_MODES.size)]

private fun genTextLanguage(rnd: Random): String? =
    when (rnd.nextInt(10)) {
        0 -> null
        1 -> randomString(rnd, 12)
        else -> TEXT_LANGUAGES[rnd.nextInt(TEXT_LANGUAGES.size)]
    }

private fun genTextStyleArg(rnd: Random): String? =
    when (rnd.nextInt(6)) {
        0 -> null
        1 -> "  "
        2 -> "语气正式"
        3 -> "  语气正式  "
        4 -> randomWord(rnd)
        else -> randomString(rnd, 12)
    }

private fun genTextTone(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 12) else TEXT_TONES[rnd.nextInt(TEXT_TONES.size)]

private fun genSuggestCount(rnd: Random): Int =
    when (rnd.nextInt(8)) {
        0 -> 0
        1 -> 1
        2 -> 4
        3 -> 5
        4 -> -1
        5 -> rnd.nextInt(-100, 100)
        6 -> Int.MIN_VALUE
        else -> Int.MAX_VALUE
    }

private fun genSummarizeStyle(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 12) else TEXT_STYLES[rnd.nextInt(TEXT_STYLES.size)]

private fun genGroupMode(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 12) else TEXT_GROUP_MODES[rnd.nextInt(TEXT_GROUP_MODES.size)]

private fun genTextQuery(rnd: Random): String =
    when (rnd.nextInt(8)) {
        0 -> "a".repeat(rnd.nextInt(0, 750))
        1 -> "😀".repeat(rnd.nextInt(0, 380))
        2 -> " " + randomWord(rnd) + " ".repeat(rnd.nextInt(0, 10))
        3 -> randomString(rnd, 1500)
        else -> randomString(rnd, 60)
    }

private fun genTranscriptMessages(rnd: Random): List<AiContextMessage> {
    val n = rnd.nextInt(6)
    return (0 until n).map {
        val sender = when (rnd.nextInt(6)) {
            0 -> "小明"
            1 -> "小红"
            2 -> ""
            3 -> " "
            4 -> randomWord(rnd)
            else -> randomString(rnd, 12)
        }
        val text = when (rnd.nextInt(6)) {
            0 -> "你好"
            1 -> "在吗"
            2 -> ""
            3 -> "a\nb"
            4 -> randomWord(rnd)
            else -> randomString(rnd, 30)
        }
        AiContextMessage(sender = sender, text = text)
    }
}

    @Test
    fun transcriptRandom() {
        val rnd = Random(42)
        repeat(300) {
            val messages = genTranscriptMessages(rnd)
            assertEquals(textTranscriptOracle(messages), AgentTextTaskPolicy.transcript(messages))
            for (m in messages) {
                assertEquals(
                    textTranscriptLineOracle(m.sender, m.text),
                    AgentTextTaskPolicy.transcriptLine(m.sender, m.text)
                )
            }
        }
    }

    @Test
    fun rewriteRandom() {
        val rnd = Random(42)
        repeat(300) {
            val mode = genTextMode(rnd)
            val lang = genTextLanguage(rnd)
            val style = genTextStyleArg(rnd)
            assertEquals(textRewriteBaseOracle(mode, lang), AgentTextTaskPolicy.rewriteBaseInstruction(mode, lang))
            assertEquals(textRewriteOracle(mode, lang, style), AgentTextTaskPolicy.rewriteInstruction(mode, lang, style))
            val tlang = lang ?: "en"
            assertEquals(textTranslateOracle(tlang), AgentTextTaskPolicy.translateInstruction(tlang))
        }
    }

    @Test
    fun suggestSummarizeGroupRandom() {
        val rnd = Random(42)
        repeat(300) {
            val tone = genTextTone(rnd)
            val count = genSuggestCount(rnd)
            assertEquals(textSuggestOracle(tone, count), AgentTextTaskPolicy.suggestInstruction(tone, count))
            val style = genSummarizeStyle(rnd)
            assertEquals(textSummarizeOracle(style), AgentTextTaskPolicy.summarizeInstruction(style))
            val mode = genGroupMode(rnd)
            val query = genTextQuery(rnd)
            assertEquals(textGroupOracle(mode, query), AgentTextTaskPolicy.groupAssistantInstruction(mode, query))
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Every rewrite mode maps, case-insensitively, after trim.
        assertEquals(
            "改写下面的草稿：缩短，保留原意。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction(" shorten ", null)
        )
        assertEquals(
            "改写下面的草稿：更正式礼貌。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("FORMAL", null)
        )
        assertEquals(
            "改写下面的草稿：更温和。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("gentle", null)
        )
        assertEquals(
            "改写下面的草稿：更口语。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("casual", null)
        )
        assertEquals(
            "改写下面的草稿：更专业商务。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("professional", null)
        )
        assertEquals(
            "改写下面的草稿：稍加展开，不编造事实。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("expand", null)
        )
        assertEquals(
            "改写下面的草稿：改成简洁条目。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("bullet", null)
        )
        assertEquals(
            "改写下面的草稿：更清楚，不改变立场。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("clarify", null)
        )
        // Unknown mode falls back to polish.
        assertEquals(
            "改写下面的草稿：润色，保持原意和语气。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("robotic", null)
        )
        // Translate branch: null / blank language falls back to 中文.
        assertEquals(
            "改写下面的草稿：翻译成 英语，只输出译文。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("translate", " 英语 ")
        )
        assertEquals(
            "改写下面的草稿：翻译成 中文，只输出译文。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("translate", "  ")
        )
        assertEquals(
            "改写下面的草稿：翻译成 中文，只输出译文。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("translate", null)
        )
        // Production-only pin: U+0130 lowercases to two units under the
        // full mapping, so it matches no mode and falls back. The oracle
        // deliberately does not generate U+0130.
        assertEquals(
            "改写下面的草稿：润色，保持原意和语气。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteBaseInstruction("İ", null)
        )
        // rewriteInstruction appends the style verbatim, only when non-blank.
        assertEquals(
            "改写下面的草稿：更正式礼貌。只输出改写结果，不要解释。 语气正式",
            AgentTextTaskPolicy.rewriteInstruction("formal", null, "语气正式")
        )
        assertEquals(
            "改写下面的草稿：缩短，保留原意。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteInstruction("shorten", null, "  ")
        )
        assertEquals(
            "改写下面的草稿：缩短，保留原意。只输出改写结果，不要解释。",
            AgentTextTaskPolicy.rewriteInstruction("shorten", null, null)
        )
        // translateInstruction trims, blank falls back to 中文.
        assertEquals("把下面文本翻译成 中文。只输出译文。", AgentTextTaskPolicy.translateInstruction("  "))
        assertEquals("把下面文本翻译成 英语。只输出译文。", AgentTextTaskPolicy.translateInstruction(" 英语 "))
        // suggestInstruction: known tone kept lowercased, count clamped 1..4.
        assertEquals(
            "根据对话写 2 条可直接发送的回复，语气 warm。每条一行，不要编号以外的解释。",
            AgentTextTaskPolicy.suggestInstruction(" warm ", 2)
        )
        assertEquals(
            "根据对话写 1 条可直接发送的回复，语气 friendly。每条一行，不要编号以外的解释。",
            AgentTextTaskPolicy.suggestInstruction("robotic", 0)
        )
        assertEquals(
            "根据对话写 4 条可直接发送的回复，语气 friendly。每条一行，不要编号以外的解释。",
            AgentTextTaskPolicy.suggestInstruction("robotic", 9)
        )
        assertEquals(
            "根据对话写 1 条可直接发送的回复，语气 friendly。每条一行，不要编号以外的解释。",
            AgentTextTaskPolicy.suggestInstruction("robotic", Int.MIN_VALUE)
        )
        assertEquals(
            "根据对话写 4 条可直接发送的回复，语气 friendly。每条一行，不要编号以外的解释。",
            AgentTextTaskPolicy.suggestInstruction("robotic", Int.MAX_VALUE)
        )
        // summarizeInstruction: all five styles, else the default.
        assertEquals(
            "总结这些本机已解密消息。风格：详细叙述。不要声称你已在聊天里执行了任何操作。",
            AgentTextTaskPolicy.summarizeInstruction("detailed")
        )
        assertEquals(
            "总结这些本机已解密消息。风格：只列已做出的决定。不要声称你已在聊天里执行了任何操作。",
            AgentTextTaskPolicy.summarizeInstruction("decisions")
        )
        assertEquals(
            "总结这些本机已解密消息。风格：只列待办，每行一条。不要声称你已在聊天里执行了任何操作。",
            AgentTextTaskPolicy.summarizeInstruction("tasks")
        )
        assertEquals(
            "总结这些本机已解密消息。风格：按时间顺序列要点。不要声称你已在聊天里执行了任何操作。",
            AgentTextTaskPolicy.summarizeInstruction("timeline")
        )
        assertEquals(
            "总结这些本机已解密消息。风格：只列风险和阻塞。不要声称你已在聊天里执行了任何操作。",
            AgentTextTaskPolicy.summarizeInstruction("risks")
        )
        assertEquals(
            "总结这些本机已解密消息。风格：简要概括。不要声称你已在聊天里执行了任何操作。",
            AgentTextTaskPolicy.summarizeInstruction("unknown")
        )
        // groupAssistantInstruction: known mode kept, query trimmed and
        // taken at 700 UTF-16 units; unknown mode falls back to answer.
        assertEquals(
            "用本机已解密的群聊上下文回答。模式=summary。用户问题：本周进展？",
            AgentTextTaskPolicy.groupAssistantInstruction(" summary ", "  本周进展？ ")
        )
        assertEquals(
            "用本机已解密的群聊上下文回答。模式=answer。用户问题：hi",
            AgentTextTaskPolicy.groupAssistantInstruction("chat", "hi")
        )
        val longQuery = "a".repeat(701)
        assertEquals(
            "用本机已解密的群聊上下文回答。模式=answer。用户问题：" + "a".repeat(700),
            AgentTextTaskPolicy.groupAssistantInstruction("chat", longQuery)
        )
        val emojiQuery = "😀".repeat(351)
        assertEquals(
            "用本机已解密的群聊上下文回答。模式=answer。用户问题：" + "😀".repeat(350),
            AgentTextTaskPolicy.groupAssistantInstruction("chat", emojiQuery)
        )
        // transcript: empty list is empty, lines join with a single newline.
        assertEquals("", AgentTextTaskPolicy.transcript(emptyList()))
        assertEquals(
            "小明: 你好\n小红: 在吗",
            AgentTextTaskPolicy.transcript(
                listOf(AiContextMessage("小明", "你好"), AiContextMessage("小红", "在吗"))
            )
        )
        assertEquals("小明: 你好", AgentTextTaskPolicy.transcriptLine("小明", "你好"))
    }
}
