// Fuzz / random-oracle tests for AiIntentNormalization (checklist line 931,
// 77th block). Test-only, zero production changes. The oracle is
// hand-written: plain logic read from the production file. normalizeAiReplyTone trims + lowercases then matches 9 tones (default friendly); resolveGroupAiMode takes an explicit mode else infers; inferGroupAiMode checks summary/decisions/tasks/timeline/risks prefixes in order; parseGroupAiTasks only runs for mode tasks; parseGroupAiCommand needs @AI + whitespace-or-end; groupAiModePrefix is a plain mapping; stripGroupAiModeKeyword strips only summary/decisions/tasks keywords.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class AiIntentNormalizationFuzzTest {

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
    0x1E00 to 0x1E95, 0x1E9C to 0x1EFF,
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

// ---------- extra hand-written helpers ----------

private fun isWhitespaceOracle(c: Char): Boolean = c.code in BLANK_UNITS_ORACLE

private fun isLetterOrDigitOracle(c: Char): Boolean =
    Character.isLetterOrDigit(c.code)

private fun startsWithOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (s[i] != prefix[i]) return false
    }
    return true
}

private fun startsWithIgnoreCaseOracle(s: String, prefix: String): Boolean {
    if (prefix.length > s.length) return false
    for (i in prefix.indices) {
        if (!charsEqualIgnoreCaseOracle(s[i], prefix[i])) return false
    }
    return true
}

private fun dropOracle(s: String, n: Int): String {
    if (n <= 0) return s
    if (n >= s.length) return ""
    return s.substring(n)
}

private fun removePrefixOracle(s: String, prefix: String): String =
    if (startsWithOracle(s, prefix)) dropOracle(s, prefix.length) else s

// trimStart() with no args: strip leading Kotlin-whitespace units.
private fun trimStartBlankOracle(s: String): String {
    var i = 0
    while (i < s.length && s[i].code in BLANK_UNITS_ORACLE) i++
    return s.substring(i)
}

// trimStart('-', '*', '•'): strip leading chars in the given set.
private fun trimStartCharsOracle(s: String): String {
    var i = 0
    while (i < s.length && (s[i] == '-' || s[i] == '*' || s[i] == '•')) i++
    return s.substring(i)
}

// lineSequence(): split on "\r\n" | "\n" | "\r", terminators dropped,
// a trailing terminator leaves a final empty line.
private fun splitLinesOracle(s: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\n') {
            out.add(s.substring(start, i))
            i++
            start = i
        } else if (c == '\r') {
            out.add(s.substring(start, i))
            i++
            if (i < s.length && s[i] == '\n') i++
            start = i
        } else {
            i++
        }
    }
    out.add(s.substring(start, s.length))
    return out
}

// ---------- AiIntentNormalization / ChatGroupAiCommand oracle ----------

private val TONES = listOf(
    "natural", "friendly", "formal", "concise", "warm", "humorous",
    "direct", "empathetic", "encouraging"
)

private val GROUP_MODES = listOf(
    "answer", "summary", "decisions", "tasks", "timeline", "risks"
)

private fun normalizeToneOracle(tone: String): String {
    val t = lowercaseOracle(trimOracle(tone))
    for (x in TONES) {
        if (equalsOracle(t, x)) return t
    }
    return "friendly"
}

private fun inferModeOracle(query: String): String {
    val n = lowercaseOracle(trimOracle(query))
    return when {
        startsWithOracle(n, "总结") || startsWithOracle(n, "概括") ||
            startsWithOracle(n, "summary") || startsWithOracle(n, "summarize") -> "summary"
        startsWithOracle(n, "决策") || startsWithOracle(n, "决定") ||
            startsWithOracle(n, "decisions") -> "decisions"
        startsWithOracle(n, "待办") || startsWithOracle(n, "任务") ||
            startsWithOracle(n, "tasks") || startsWithOracle(n, "todo") -> "tasks"
        startsWithOracle(n, "时间线") || startsWithOracle(n, "时间轴") ||
            startsWithOracle(n, "timeline") || startsWithOracle(n, "chronology") -> "timeline"
        startsWithOracle(n, "风险") || startsWithOracle(n, "隐患") ||
            startsWithOracle(n, "risk") || startsWithOracle(n, "blocker") -> "risks"
        else -> "answer"
    }
}

private fun resolveModeOracle(mode: String?, query: String): String {
    if (mode != null) {
        val m = lowercaseOracle(trimOracle(mode))
        for (x in GROUP_MODES) {
            if (equalsOracle(m, x)) return m
        }
    }
    return inferModeOracle(query)
}

private fun parseTasksOracle(answer: String, mode: String): List<String> {
    if (!equalsOracle(mode, "tasks")) return emptyList()
    val out = mutableListOf<String>()
    for (raw in splitLinesOracle(answer)) {
        val line = trimStartCharsOracle(trimOracle(raw))
        if (!isBlankOracle(line)) {
            out.add(takeOracle(line, 300))
            if (out.size >= 30) break
        }
    }
    return out
}

private fun parseCommandOracle(value: String): String? {
    val t = trimOracle(value)
    if (!startsWithIgnoreCaseOracle(t, "@AI")) return null
    if (t.length > 3 && !isWhitespaceOracle(t[3])) return null
    return trimOracle(dropOracle(t, 3))
}

private fun modePrefixOracle(mode: String): String = when (mode) {
    "summary" -> "总结 "
    "decisions" -> "决策 "
    "tasks" -> "待办 "
    "timeline" -> "时间线 "
    "risks" -> "风险 "
    else -> ""
}

private fun stripModeKeywordOracle(query: String): String {
    val lower = lowercaseOracle(query)
    return when {
        startsWithOracle(query, "总结") || startsWithOracle(query, "概括") ->
            trimStartBlankOracle(removePrefixOracle(removePrefixOracle(query, "总结"), "概括"))
        startsWithOracle(lower, "summary") -> trimStartBlankOracle(dropOracle(query, 7))
        startsWithOracle(lower, "summarize") -> trimStartBlankOracle(dropOracle(query, 9))
        startsWithOracle(query, "决策") || startsWithOracle(query, "决定") ->
            trimStartBlankOracle(removePrefixOracle(removePrefixOracle(query, "决策"), "决定"))
        startsWithOracle(lower, "decisions") -> trimStartBlankOracle(dropOracle(query, 9))
        startsWithOracle(query, "待办") || startsWithOracle(query, "任务") ->
            trimStartBlankOracle(removePrefixOracle(removePrefixOracle(query, "待办"), "任务"))
        startsWithOracle(lower, "tasks") -> trimStartBlankOracle(dropOracle(query, 5))
        startsWithOracle(lower, "todo") -> trimStartBlankOracle(dropOracle(query, 4))
        else -> query
    }
}

private val INTENT_TONE_POOL = listOf(
    "natural", "friendly", "formal", "concise", "warm", "humorous",
    "direct", "empathetic", "encouraging", "FRIENDLY", "Friendly",
    "  friendly  ", "\tformal\n", "NATURAL", "Warm", "unknown", "fr",
    "friendlys", "friendl", "", " ", "cold", "serious"
)

private val INTENT_MODE_POOL = listOf(
    "answer", "summary", "decisions", "tasks", "timeline", "risks",
    "SUMMARY", "Summary", "  tasks  ", "task", "taskss", "summaries",
    "", " ", "chat", "answer "
)

private val INTENT_QUERY_PREFIXES = listOf(
    "总结", "概括", "summary", "summarize", "SUMMARY", "Summarize",
    "决策", "决定", "decisions", "DECISIONS",
    "待办", "任务", "tasks", "todo", "TODO", "Tasks",
    "时间线", "时间轴", "timeline", "chronology", "TIMELINE",
    "风险", "隐患", "risk", "blocker", "BLOCKER",
    "  总结", "  summary", "请总结", "帮我总结", "hello", "你好"
)

private val INTENT_COMMAND_POOL = listOf(
    "@AI", "@ai", "@Ai", "@AI ", "@AI  总结本周", "@ai\t总结",
    "@AI\ntasks", "@AIx", "@AI-foo", "@A", "@", " @AI 总结",
    "x@AI", "@AI  ", "  @AI  ", "@aI 决策", "@AI\u00A0总结"
)

private fun genTone(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> INTENT_TONE_POOL[rnd.nextInt(INTENT_TONE_POOL.size)]
        6 -> TONES[rnd.nextInt(TONES.size)] + "s"
        7 -> TONES[rnd.nextInt(TONES.size)].dropLast(1)
        8 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }

private fun genMode(rnd: Random): String? =
    when (rnd.nextInt(10)) {
        0 -> null
        1, 2, 3, 4, 5 -> INTENT_MODE_POOL[rnd.nextInt(INTENT_MODE_POOL.size)]
        6 -> GROUP_MODES[rnd.nextInt(GROUP_MODES.size)] + "s"
        7 -> randomToken(rnd)
        else -> randomString(rnd, 20)
    }

private fun genQuery(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> {
            val p = INTENT_QUERY_PREFIXES[rnd.nextInt(INTENT_QUERY_PREFIXES.size)]
            p + when (rnd.nextInt(4)) {
                0 -> ""
                1 -> " " + randomWord(rnd)
                2 -> "本周的工作"
                else -> randomString(rnd, 12)
            }
        }
        6 -> randomToken(rnd)
        7 -> randomWord(rnd)
        else -> randomString(rnd, 24)
    }

private val INTENT_ANSWER_LINES = listOf(
    "- 买菜", "* 开会", "• 写报告", "  买菜  ", "- ", "* ", "", "   ",
    "待办事项", "-  " + "x".repeat(350), "plain line", "  - 缩进任务",
    "•", "-", "1. 第一步", "— 破折号", "-a", "*b"
)

private fun genAnswer(rnd: Random): String {
    val n = rnd.nextInt(0, 8)
    val sep = when (rnd.nextInt(4)) {
        0 -> "\n"
        1 -> "\r\n"
        2 -> "\r"
        else -> "\n"
    }
    val lines = List(n) {
        when (rnd.nextInt(10)) {
            0, 1, 2, 3, 4, 5 -> INTENT_ANSWER_LINES[rnd.nextInt(INTENT_ANSWER_LINES.size)]
            6 -> randomWord(rnd)
            7 -> randomToken(rnd)
            else -> randomString(rnd, 30)
        }
    }
    val joined = lines.joinToString(sep)
    return if (rnd.nextInt(4) == 0) joined + sep else joined
}

private fun genTasksMode(rnd: Random): String =
    when (rnd.nextInt(6)) {
        0, 1, 2 -> "tasks"
        3 -> "TASKS"
        4 -> "summary"
        else -> randomToken(rnd)
    }

private fun genCommand(rnd: Random): String =
    when (rnd.nextInt(10)) {
        0, 1, 2, 3, 4, 5 -> INTENT_COMMAND_POOL[rnd.nextInt(INTENT_COMMAND_POOL.size)]
        6 -> "@AI " + randomWord(rnd)
        7 -> randomToken(rnd)
        else -> randomString(rnd, 16)
    }

    @Test
    fun intentRandom() {
        val rnd = Random(42)
        repeat(300) {
            val tone = genTone(rnd)
            val mode = genMode(rnd)
            val query = genQuery(rnd)
            val answer = genAnswer(rnd)
            val tasksMode = genTasksMode(rnd)
            val command = genCommand(rnd)
            assertEquals(normalizeToneOracle(tone), normalizeAiReplyTone(tone))
            assertEquals(resolveModeOracle(mode, query), resolveGroupAiMode(mode, query))
            assertEquals(inferModeOracle(query), inferGroupAiMode(query))
            assertEquals(
                parseTasksOracle(answer, tasksMode),
                parseGroupAiTasks(answer, tasksMode).map { it.title }
            )
            assertEquals(parseCommandOracle(command), parseGroupAiCommand(command))
            assertEquals(modePrefixOracle(tasksMode), groupAiModePrefix(tasksMode))
            assertEquals(stripModeKeywordOracle(query), stripGroupAiModeKeyword(query))
        }
    }

    @Test
    fun intentCommandRandom() {
        val rnd = Random(7)
        repeat(300) {
            // Command-heavy: the "@AI" prefix, the char after it and
            // the trimmed rest decide null vs value.
            val command = genCommand(rnd)
            val query = genQuery(rnd)
            assertEquals(parseCommandOracle(command), parseGroupAiCommand(command))
            assertEquals(stripModeKeywordOracle(query), stripGroupAiModeKeyword(query))
            assertEquals(inferModeOracle(query), inferGroupAiMode(query))
        }
    }
    @Test
    fun pinnedEdgeCases() {
        // Tones: exact set after trim + lowercase, default friendly.
        assertEquals("friendly", normalizeAiReplyTone("friendly"))
        assertEquals("friendly", normalizeAiReplyTone("  FRIENDLY  "))
        assertEquals("formal", normalizeAiReplyTone("Formal"))
        assertEquals("friendly", normalizeAiReplyTone("cold"))
        assertEquals("friendly", normalizeAiReplyTone(""))
        assertEquals("friendly", normalizeAiReplyTone("   "))
        assertEquals("warm", normalizeAiReplyTone("warm"))
        // Modes: explicit mode wins, else infer from the query.
        assertEquals("summary", resolveGroupAiMode("summary", "待办买菜"))
        assertEquals("tasks", resolveGroupAiMode("  TASKS ", "总结本周"))
        assertEquals("summary", resolveGroupAiMode(null, "总结本周"))
        assertEquals("answer", resolveGroupAiMode(null, "随便聊聊"))
        assertEquals("answer", resolveGroupAiMode("chat", "随便聊聊"))
        assertEquals("decisions", resolveGroupAiMode(null, "决策：是否上线"))
        assertEquals("tasks", resolveGroupAiMode(null, "todo list"))
        assertEquals("timeline", resolveGroupAiMode(null, "Timeline of events"))
        assertEquals("risks", resolveGroupAiMode(null, "blocker: 依赖未到"))
        // Inference order: summary beats tasks when both prefixes
        // could match -- only the first prefix decides.
        assertEquals("summary", inferGroupAiMode("总结待办"))
        assertEquals("answer", inferGroupAiMode(""))
        assertEquals("answer", inferGroupAiMode("   "))
        // Tasks: only mode == "tasks" parses; lines split on any
        // newline form, prefixes stripped, blanks dropped, 30 cap,
        // titles take 300.
        assertEquals(emptyList(), parseGroupAiTasks("- 买菜", "summary").map { it.title })
        assertEquals(
            listOf(" 买菜", " 开会"),
            parseGroupAiTasks("- 买菜\n* 开会", "tasks").map { it.title }
        )
        assertEquals(
            listOf(" 买菜", " 开会"),
            parseGroupAiTasks("- 买菜\r\n* 开会\r", "tasks").map { it.title }
        )
        assertEquals(
            listOf(" 买菜"),
            parseGroupAiTasks("- 买菜\n\n   \n- ", "tasks").map { it.title }
        )
        assertEquals(
            listOf(" " + "x".repeat(299)),
            parseGroupAiTasks("- " + "x".repeat(350), "tasks").map { it.title }
        )
        assertEquals(
            30,
            parseGroupAiTasks((1..40).joinToString("\n") { "- 任务$it" }, "tasks").size
        )
        assertEquals(
            emptyList(),
            parseGroupAiTasks("•", "tasks").map { it.title }
        )
        // Commands: "@AI" + whitespace-or-end, rest trimmed.
        assertEquals("总结本周", parseGroupAiCommand("@AI 总结本周"))
        assertEquals("总结本周", parseGroupAiCommand("  @ai\t总结本周  "))
        assertEquals("", parseGroupAiCommand("@AI"))
        assertEquals("", parseGroupAiCommand("@AI "))
        assertEquals(null, parseGroupAiCommand("@AIx"))
        assertEquals(null, parseGroupAiCommand("@A"))
        assertEquals(null, parseGroupAiCommand("hello"))
        assertEquals(null, parseGroupAiCommand("@AI-foo"))
        // Prefixes: plain mapping, unknown is "".
        assertEquals("总结 ", groupAiModePrefix("summary"))
        assertEquals("待办 ", groupAiModePrefix("tasks"))
        assertEquals("", groupAiModePrefix("answer"))
        assertEquals("", groupAiModePrefix("unknown"))
        // Strip: only summary/decisions/tasks keywords are stripped;
        // timeline/risks pass through unchanged.
        assertEquals("本周的工作", stripGroupAiModeKeyword("总结 本周的工作"))
        assertEquals("本周的工作", stripGroupAiModeKeyword("概括本周的工作"))
        assertEquals("本周", stripGroupAiModeKeyword("总结概括 本周"))
        assertEquals("本周的工作", stripGroupAiModeKeyword("SUMMARY 本周的工作"))
        assertEquals("本周的工作", stripGroupAiModeKeyword("summarize 本周的工作"))
        assertEquals("是否上线", stripGroupAiModeKeyword("决策 是否上线"))
        assertEquals("买菜", stripGroupAiModeKeyword("待办 买菜"))
        assertEquals("买菜", stripGroupAiModeKeyword("todo 买菜"))
        assertEquals("时间线 本周", stripGroupAiModeKeyword("时间线 本周"))
        assertEquals("risk list", stripGroupAiModeKeyword("risk list"))
        assertEquals("随便聊聊", stripGroupAiModeKeyword("随便聊聊"))
    }
}
