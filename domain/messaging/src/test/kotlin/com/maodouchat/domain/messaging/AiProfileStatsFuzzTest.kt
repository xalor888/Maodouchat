package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import java.util.Calendar
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// unescapeHtmlEntities restated by hand: numeric decimal entities,
// then numeric hex entities, then the named replacements in order.
// A code outside 1..0x10FFFF leaves the entity untouched; a lone
// surrogate code point becomes one lone surrogate unit, exactly as
// String(intArrayOf(code), 0, 1) builds it.
internal val NUMERIC_DECIMAL176 = Regex("&#(\\d{1,7});")
internal val NUMERIC_HEX176 = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)

internal fun unescapeHtmlEntitiesOracle176(raw: String): String {
    if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
    var out = raw
    out = NUMERIC_DECIMAL176.replace(out) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    out = NUMERIC_HEX176.replace(out) { match ->
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
internal fun parsedContentOracle176(content: String): String =
    content.lastIndexOf(Message.META_TAG_PREFIX).let { idx ->
        val body = if (idx < 0) content else content.substring(0, idx)
        unescapeHtmlEntitiesOracle176(body)
    }

// collectTerms restated by hand: lowercase, drop whitespace and
// digits, CJK bigrams over runs of CJK chars, Latin words of 3..20
// letters split on anything not a-z0-9, minus the stop words.
internal val PROFILE_WORD_SPLIT176 = Regex("[^a-z0-9]+")
internal val PROFILE_EMOJI176 = Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF\u2600-\u27BF\uFE0F]""")
internal val PROFILE_STOP_WORDS176 = setOf(
    "the", "and", "for", "you", "your", "that", "this", "with", "have",
    "from", "are", "not", "was", "has", "but", "what", "all", "can",
    "out", "who", "when", "will", "just", "like", "about"
)

internal fun collectTermsOracle176(text: String, counts: MutableMap<String, Int>) {
    val cleaned = text.lowercase().filterNot { it.isWhitespace() || it.isDigit() }
    val cjk = StringBuilder()
    cleaned.forEach { char ->
        if (char.code in 0x4E00..0x9FFF) cjk.append(char) else cjk.append(' ')
    }
    cjk.split(' ').forEach { segment ->
        if (segment.length >= 2) {
            for (index in 0 until segment.length - 1) {
                val bigram = segment.substring(index, index + 2)
                counts[bigram] = (counts[bigram] ?: 0) + 1
            }
        }
    }
    cleaned.split(PROFILE_WORD_SPLIT176).forEach { word ->
        if (word.length in 3..20 && PROFILE_STOP_WORDS176.none { it == word }) {
            counts[word] = (counts[word] ?: 0) + 1
        }
    }
}

// computeStats restated by hand: empty input is the zero stats;
// otherwise the active days are distinct calendar days, the hour
// buckets split on 5/12/18, emoji are counted by the production
// EMOJI_PATTERN regex verbatim (Java folds the adjacent \uDBFF\uDC00
// in the pattern into U+10FC00, so '-' counts as a literal member and
// lone E000-FFFF units count), and the top terms sort by count desc
// then term asc.
internal fun computeStatsOracle176(messages: List<Message>): LocalStats {
    if (messages.isEmpty()) return LocalStats()
    val days = messages.map {
        Calendar.getInstance().apply { timeInMillis = it.timestamp }.get(Calendar.DAY_OF_YEAR)
    }.toSet()
    var morning = 0
    var afternoon = 0
    var evening = 0
    var night = 0
    var emoji = 0
    val termCounts = HashMap<String, Int>()
    for (message in messages) {
        val hour = Calendar.getInstance().apply { timeInMillis = message.timestamp }
            .get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> morning++
            in 12..17 -> afternoon++
            in 18..23 -> evening++
            else -> night++
        }
        val text = parsedContentOracle176(message.content)
        emoji += PROFILE_EMOJI176.findAll(text).count()
        collectTermsOracle176(text, termCounts)
    }
    val topTerms = termCounts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
        .take(10)
    return LocalStats(
        messageCount = messages.size,
        activeDays = days.size,
        morning = morning,
        afternoon = afternoon,
        evening = evening,
        night = night,
        emojiCount = emoji,
        topTerms = topTerms
    )
}

// Production computeStats builds the same local profile stats.
internal fun computeStatsProduction176(messages: List<Message>): LocalStats =
    AiProfileStats.computeStats(messages)

// ---------- generators ----------

internal data class ProfileMessageCase176(
    val content: String,
    val timestamp: Long,
)

internal data class ProfileCase176(
    val messages: List<ProfileMessageCase176>,
)

internal val PROFILE_CONTENT_POOL176: List<String> = listOf(
    "", " ", "plain text", "hello world hello", "the and for you",
    "你好世界", "你好你好世界", "中文测试文本内容", "a你b好c",
    "hi \uD83D\uDE00", "\uD83D\uDE00\uD83D\uDE00", "abc\u2600def",
    "123 456", "a1b2c3", "word123word", "ab", "abc", "abcd",
    "longword".repeat(4), "x".repeat(25), "hello, world! how are you?",
    "UPPER lower MiXeD", "tab\there", "line\nbreak", "  spaced  out  ",
    "&#65;bc", "&lt;tag&gt;", "a&lt;b", "&#x41;bc",
    "text<meta>{\"fileName\":\"a.pdf\"}</meta>", "a<meta>b",
    "<meta>", "a &amp; b &amp; c", "caf\u00e9 na\u00efve",
    "\uD83D\uDE00 test \u2600 ok", "a\u00a0b", "a\u2007b",
    "\u00b2\u00bd", "x\u0660y", "\uD83C\uDF89party",
)

internal val PROFILE_TIMESTAMP_POOL176: List<Long> = listOf(
    0L, 1L, -1L, -86400000L, 86399999L, 946684800000L,
    631152000000L, 662688000000L, 575856000000L, 599616000000L,
    1700000000000L, 4102444800000L, -2208988800000L,
    581126400000L, 646790400000L,
)

internal val PROFILE_ALPHABET176: String =
    "abcXYZ 0123456789.,!?<>&;=\u00e9\u4e2d\u6587\uD83D\uDE00\u2600\n\t"

internal fun randomProfileCase176(rnd: Random): ProfileCase176 {
    val n = rnd.nextInt(0, 5)
    val messages = ArrayList<ProfileMessageCase176>(n)
    repeat(n) {
        val len = rnd.nextInt(0, 25)
        val sb = StringBuilder()
        repeat(len) {
            sb.append(PROFILE_ALPHABET176[rnd.nextInt(PROFILE_ALPHABET176.length)])
        }
        val content = if (rnd.nextInt(4) == 0) {
            PROFILE_CONTENT_POOL176[rnd.nextInt(PROFILE_CONTENT_POOL176.size)]
        } else {
            sb.toString()
        }
        val timestamp = when (rnd.nextInt(4)) {
            0 -> PROFILE_TIMESTAMP_POOL176[rnd.nextInt(PROFILE_TIMESTAMP_POOL176.size)]
            1 -> rnd.nextLong(0L, 4102444800000L)
            2 -> rnd.nextLong(-2208988800000L, 4102444800000L)
            else -> rnd.nextLong()
        }
        messages.add(ProfileMessageCase176(content, timestamp))
    }
    return ProfileCase176(messages)
}

internal fun buildProfileMessages176(c: ProfileCase176): List<Message> =
    c.messages.mapIndexed { index, m ->
        Message(
            id = "m$index",
            chatId = "c1",
            senderId = "u1",
            content = m.content,
            timestamp = m.timestamp
        )
    }

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonProfile176(s: String): String {
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

internal fun profileWireJson176(c: ProfileCase176): String {
    val parts = c.messages.joinToString(",") { m ->
        "{\"content\":${escJsonProfile176(m.content)},\"timestamp\":${m.timestamp}}"
    }
    return "{\"messages\":[$parts]}"
}

internal fun profileProjectionJson176(s: LocalStats): String {
    val terms = s.topTerms.joinToString(",") { escJsonProfile176(it) }
    return "{\"messageCount\":${s.messageCount}," +
        "\"activeDays\":${s.activeDays}," +
        "\"morning\":${s.morning}," +
        "\"afternoon\":${s.afternoon}," +
        "\"evening\":${s.evening}," +
        "\"night\":${s.night}," +
        "\"emojiCount\":${s.emojiCount}," +
        "\"topTerms\":[$terms]}"
}

class AiProfileStatsFuzzTest {

    private fun checkProfile176(c: ProfileCase176) {
        val messages = buildProfileMessages176(c)
        assertEquals(
            profileProjectionJson176(computeStatsOracle176(messages)),
            profileProjectionJson176(computeStatsProduction176(messages)),
            "profile($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Empty input, each pooled content at the epoch, and each
        // pooled timestamp on one plain message: the calendar buckets
        // (incl. DST-era and pre-1970 stamps) and the term splitter
        // are pinned before any random input runs.
        checkProfile176(ProfileCase176(emptyList()))
        for (content in PROFILE_CONTENT_POOL176) {
            checkProfile176(ProfileCase176(listOf(ProfileMessageCase176(content, 0L))))
        }
        for (timestamp in PROFILE_TIMESTAMP_POOL176) {
            checkProfile176(ProfileCase176(listOf(ProfileMessageCase176("hello world", timestamp))))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(176001)
        repeat(4000) { checkProfile176(randomProfileCase176(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Every pooled content at every pooled timestamp, plus every
        // one- and two-letter string over the core letters (catches
        // term-length, stop-word and bigram slips the pool misses).
        for (content in PROFILE_CONTENT_POOL176) {
            for (timestamp in PROFILE_TIMESTAMP_POOL176) {
                checkProfile176(ProfileCase176(listOf(ProfileMessageCase176(content, timestamp))))
            }
        }
        val letters = "ab \u4e2d\u6587"
        for (a in letters) {
            checkProfile176(ProfileCase176(listOf(ProfileMessageCase176(a.toString(), 1700000000000L))))
            for (b in letters) {
                checkProfile176(ProfileCase176(listOf(ProfileMessageCase176("$a$b", 1700000000000L))))
            }
        }
    }
}
