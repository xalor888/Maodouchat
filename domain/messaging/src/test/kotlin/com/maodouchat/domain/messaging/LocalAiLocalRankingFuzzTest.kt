package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

// ---------- oracle ----------

// Local semantic ranking restated by hand: tokenize the lowercased
// trimmed query on Java `\s+` (ASCII whitespace only), keep the first
// 12 non-blank tokens, score each candidate by how many tokens occur
// in its lowercased text (duplicate tokens count twice), drop zero
// scores, sort by score descending (stable: ties keep candidate
// order). Ground truth, probed with kotlinc 2.4.0:
//   - String.lowercase() is full-Unicode: 'İ' -> "i̇", 'Σ' at a word
//     end -> 'ς' (final sigma; Python str.lower() does NOT do this,
//     so the Python model implements it by hand).
//   - trim()/isBlank() strip exactly Character.isWhitespace (the
//     WS_RANK181 set below; U+0085 and U+FEFF are NOT stripped).
//   - split on `\s+` keeps leading/trailing empties (they are blank
//     and filtered out anyway); U+00A0 never splits (not Java `\s`).
internal val WS_RANK181: Set<Int> = setOf(
    9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194,
    8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288
)

internal fun isJavaWsRank181(cp: Int): Boolean =
    cp == 0x20 || cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D

internal fun isBlankRank181(s: String): Boolean {
    if (s.isEmpty()) return true
    for (ch in s) if (ch.code !in WS_RANK181) return false
    return true
}

internal fun trimWsRank181(s: String): String {
    var a = 0
    var b = s.length
    while (a < b && s[a].code in WS_RANK181) a++
    while (b > a && s[b - 1].code in WS_RANK181) b--
    return s.substring(a, b)
}

internal fun splitJavaWsRank181(s: String): List<String> {
    val out = ArrayList<String>()
    var i = 0
    var start = 0
    while (i < s.length) {
        if (isJavaWsRank181(s[i].code)) {
            out.add(s.substring(start, i))
            while (i < s.length && isJavaWsRank181(s[i].code)) i++
            start = i
        } else {
            i++
        }
    }
    out.add(s.substring(start, s.length))
    return out
}

internal fun rankScoredOracle181(
    query: String,
    candidates: List<Pair<String, String>>,
): List<Pair<String, Double>> {
    val q = trimWsRank181(query).lowercase()
    if (isBlankRank181(q)) return emptyList()
    val tokens = splitJavaWsRank181(q).filter { !isBlankRank181(it) }.take(12)
    return candidates
        .map { (id, text) ->
            val hay = text.lowercase()
            val score = tokens.count { token -> hay.contains(token) }.toDouble()
            id to score
        }
        .filter { it.second > 0.0 }
        .sortedByDescending { it.second }
}

internal fun rankOracle181(
    query: String,
    candidates: List<Pair<String, String>>,
): List<String> = rankScoredOracle181(query, candidates).map { it.first }

internal fun escJsonRank181(s: String): String {
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

internal fun rankWire181(scored: List<Pair<String, Double>>): String {
    val s = scored.joinToString(",") { (id, score) ->
        "[" + escJsonRank181(id) + "," + score.toString() + "]"
    }
    val ids = scored.joinToString(",") { (id, _) -> escJsonRank181(id) }
    return "{\"scored\":[$s],\"ids\":[$ids]}"
}

// ---------- production ----------

// Production wrappers: same wire format as the oracle.
internal fun rankScoredProduction181(
    query: String,
    candidates: List<Pair<String, String>>,
): String = runBlocking {
    rankWire181(LocalAiLocalRanking.rankSemanticScored(query, candidates))
}

internal fun rankProduction181(
    query: String,
    candidates: List<Pair<String, String>>,
): String = runBlocking {
    val scored = LocalAiLocalRanking.rankSemanticScored(query, candidates)
    val ids = LocalAiLocalRanking.rankSemantic(query, candidates)
    val s = scored.joinToString(",") { (id, score) ->
        "[" + escJsonRank181(id) + "," + score.toString() + "]"
    }
    val idList = ids.joinToString(",") { escJsonRank181(it) }
    "{\"scored\":[$s],\"ids\":[$idList]}"
}

// ---------- generators ----------

internal data class RankCase181(
    val query: String,
    val candidates: List<Pair<String, String>>,
)

internal val RANK_WORDS181: List<String> = listOf(
    "hello", "Hello", "HELLO", "world", "World", "chat", "Chat", "message",
    "hello world", "a", "A", "aa", "ab", "test", "Test", "TEST", "maodou",
    "Σ", "ΣΣ", "ΟΣ", "ΟΣΟΣ", "İ", "ı", "ſ", "K", "ß", "ﬀ", "café",
    "naïve", "résumé", "emoji", "\uD83D\uDE00", "123", "x", "xy", "xyz",
    "hello-world", "don't", "l'amour", "a\u00A0b", "a\u2003b", "a\tb",
)

internal val RANK_TEXTS181: List<String> = listOf(
    "",
    "hello world",
    "Hello World",
    "HELLO WORLD",
    "say hello to the world",
    "chat message test",
    "a aa ab test",
    "ΟΣΟΣ test",
    "ΟΣ",
    "ΣΣ",
    "İSTANBUL",
    "istanbul",
    "ſtraße",
    "Kelvin",
    "café naïve résumé",
    "emoji \uD83D\uDE00 test",
    "123 456",
    "x xy xyz",
    "hello-world don't",
    "a\u00A0b c",
    "nothing matching here",
    "HELLO hello Hello",
)

internal fun randomRankCase181(rnd: Random): RankCase181 {
    val qn = rnd.nextInt(0, 5)
    val query = buildString {
        repeat(qn) {
            if (rnd.nextInt(3) == 0) {
                append(RANK_WORDS181[rnd.nextInt(RANK_WORDS181.size)])
            } else {
                val len = rnd.nextInt(0, 8)
                val alphabet = "abcXYZ 019\t\n\u00A0\u2003\u0085ΣİſKß"
                repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) }
            }
            if (rnd.nextInt(2) == 0) append(" ")
        }
    }
    val cn = rnd.nextInt(0, 6)
    val candidates = ArrayList<Pair<String, String>>()
    repeat(cn) { i ->
        val text = if (rnd.nextInt(3) == 0) {
            RANK_TEXTS181[rnd.nextInt(RANK_TEXTS181.size)]
        } else {
            val len = rnd.nextInt(0, 20)
            val alphabet = "abcXYZ 019\t\n\u00A0\u2003Σİſ"
            buildString {
                repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) }
            }
        }
        candidates.add("c$i" to text)
    }
    return RankCase181(query, candidates)
}

internal fun rankCaseWireJson181(c: RankCase181): String {
    val cands = c.candidates.joinToString(",") { (id, text) ->
        "[" + escJsonRank181(id) + "," + escJsonRank181(text) + "]"
    }
    return "{\"query\":" + escJsonRank181(c.query) + ",\"candidates\":[$cands]}"
}

internal val RANK_PINNED181: List<RankCase181> = listOf(
    RankCase181("", listOf("a" to "hello")),
    RankCase181("   ", listOf("a" to "hello")),
    RankCase181("\u00A0", listOf("a" to "hello")),
    RankCase181("hello", listOf("a" to "say hello", "b" to "nothing", "c" to "HELLO")),
    RankCase181("HELLO", listOf("a" to "say hello")),
    RankCase181("hello world", listOf("a" to "hello", "b" to "world", "c" to "hello world")),
    RankCase181("hello hello", listOf("a" to "hello", "b" to "hello hello")),
    RankCase181("ΟΣ", listOf("a" to "ΟΣΟΣ", "b" to "οσ", "c" to "ΟΣ")),
    RankCase181("ΟΣΟΣ", listOf("a" to "οσ", "b" to "ΟΣΟΣ")),
    RankCase181("İ", listOf("a" to "i̇", "b" to "I")),
    RankCase181("ſ", listOf("a" to "ſ", "b" to "s")),
    RankCase181("K", listOf("a" to "k", "b" to "K")),
    RankCase181("a a a a a a a a a a a a a", listOf("a" to "a")),
    RankCase181("one two three four five six seven eight nine ten eleven twelve thirteen", listOf("a" to "thirteen")),
    RankCase181("a\u00A0b", listOf("a" to "a\u00A0b", "b" to "a b")),
    RankCase181("a\tb", listOf("a" to "a b", "b" to "a\tb")),
    RankCase181("x", listOf()),
    RankCase181("test", listOf("a" to "test", "b" to "test", "c" to "TEST", "d" to "testing")),
)

class LocalAiLocalRankingFuzzTest {

    private fun checkRank181(c: RankCase181) {
        val oracle = rankWire181(rankScoredOracle181(c.query, c.candidates))
        assertEquals(oracle, rankScoredProduction181(c.query, c.candidates), "rankScored($c)")
        val oracleIds = rankWire181(rankScoredOracle181(c.query, c.candidates))
        assertEquals(oracleIds, rankProduction181(c.query, c.candidates), "rank($c)")
    }

    @Test
    fun pinnedRanking() {
        for (c in RANK_PINNED181) checkRank181(c)
        // Token-count boundary: the 13th token never scores.
        val thirteen = (1..13).joinToString(" ") { "t$it" }
        checkRank181(RankCase181(thirteen, listOf("a" to "t13", "b" to "t12 t13")))
        val twelve = (1..12).joinToString(" ") { "t$it" }
        checkRank181(RankCase181(twelve, listOf("a" to "t12", "b" to "t1 t12")))
        // Ties keep candidate order.
        checkRank181(
            RankCase181(
                "hello",
                listOf("first" to "hello", "second" to "hello", "third" to "HELLO"),
            )
        )
    }

    @Test
    fun randomRankingMatchesProduction() {
        val rnd = Random(181002)
        repeat(3000) { checkRank181(randomRankCase181(rnd)) }
    }

    @Test
    fun sweepRankingBoundaries() {
        // Blank-query flavours.
        for (q in listOf("", " ", "\t\n", "\u00A0", "\u2003", "\u0085", "\uFEFF", " \u00A0 ")) {
            checkRank181(RankCase181(q, listOf("a" to "hello")))
        }
        // Whitespace flavours inside the query.
        for (sep in listOf(" ", "\t", "\n", "\u000B", "\u000C", "\r", "\u00A0", "\u2003")) {
            checkRank181(RankCase181("hello" + sep + "world", listOf("a" to "hello world")))
        }
        // Final-sigma and case edges.
        for ((q, t) in listOf(
            "ΟΣ" to "ΟΣ",
            "ΟΣ" to "οσ",
            "ΟΣΟΣ" to "οσ",
            "ΟΣΟΣ" to "οσοσ",
            "Σ" to "ς",
            "Σ" to "σ",
            "İ" to "i̇",
            "I" to "ı",
            "ı" to "I",
        )) {
            checkRank181(RankCase181(q, listOf("a" to t)))
        }
        // Duplicate tokens count twice; empty candidates score nothing.
        checkRank181(RankCase181("a a a", listOf("x" to "a", "y" to "b")))
        checkRank181(RankCase181("a", listOf("x" to "", "y" to " ")))
    }
}
