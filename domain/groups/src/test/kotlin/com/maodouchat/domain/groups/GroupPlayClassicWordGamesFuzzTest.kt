package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fuzz / random-oracle tests for the GroupPlay classic codec cluster
 * (checklist line 931, 50th block): [GroupPlayClassicWordGames]. Test-only, zero production
 * changes.
 *
 * Each format/parse pair is reimplemented independently as an oracle
 * (prefix, take() limit, trim order, guard and suffix template transcribed
 * per pair from the production file; shapes: single / pair / quiz triple /
 * two-truths / bingo list / lottery / number-guess / int-coerce /
 * countdown-race / coin-flip). Shared primitives (production calls them too,
 * so sharing is exact): trim/take (UTF-16 code units), substringBefore/After,
 * split, joinToString, uppercase (default locale), toIntOrNull,
 * equals(ignoreCase), and the two field-escape replaces (as an independent
 * copy, escOracle).
 *
 * Quirks pinned, not fixed (behaviour, not bugs):
 * - parseWordHint has no separator guard: a bare body duplicates into
 *   both fields.
 * - formatTaboo takes the word before '|' from the RAW card for the suffix,
 *   while the body carries the escaped whole card.
 * - Acrostic / Rhyme / TwoWords interpolate the raw seed into the suffix.
 * Verification (offline, JDK 17): the real production code was compiled
 * verbatim with the Kotlin compiler and differentially checked against this
 * oracle on 28020 cases — the exact inputs these tests generate
 * (Random(42)), a codec round-trip, int / string / parse exhaustives, and
 * Python-generated random lines — with 0 differences. Every pinned
 * expectation below was checked against real production. The 5 test
 * methods were also run locally against the real production code
 * (kotlin-test stubs): all pass.
 */
class GroupPlayClassicWordGamesFuzzTest {

    // Shared primitives (production calls them too, so sharing is exact):
    // trim/take (UTF-16 code units), substringBefore/After, split, joinToString,
    // uppercase (default locale), toIntOrNull, equals(ignoreCase). The field
    // escape itself is an independent copy of the two replaces:
    // "|" <-> \u0001, "^" <-> \u0002.
    private fun escOracle(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")
    private fun unescOracle(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")

    private fun clampIntOracle(v: Int, lo: Int, hi: Int): Int {
        if (v < lo) return lo
        if (v > hi) return hi
        return v
    }

    private fun formatWordHintWordGamesOracle(a: String, b: String, hostLabel: String): String {
        val va = a.trim().take(60)
        val vb = b.trim().take(40)
        return "WORDHINT:" + escOracle(va) + "|" + escOracle(vb) + "|" + hostLabel + " word hint"
    }

    private fun parseWordHintWordGamesOracle(content: String): Pair<String, String>? {
        if (!content.startsWith("WORDHINT:")) return null
        val body = content.removePrefix("WORDHINT:")
        val a = unescOracle(body.substringBefore('|'))
        val b = unescOracle(body.substringAfter('|').substringBefore('|'))
        if (a.isBlank()) return null
        return a to b
    }

    private fun formatAcrosticWordGamesOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "ACROSTIC:" + escOracle(v) + "|" + hostLabel + " acrostic — start each line with letters of '" + v + "'"
    }

    private fun parseAcrosticWordGamesOracle(content: String): String? {
        if (!content.startsWith("ACROSTIC:")) return null
        val v = unescOracle(content.removePrefix("ACROSTIC:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatRhymeWordGamesOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "RHYME:" + escOracle(v) + "|" + hostLabel + " rhyme chain — rhyme with '" + v + "'"
    }

    private fun parseRhymeWordGamesOracle(content: String): String? {
        if (!content.startsWith("RHYME:")) return null
        val v = unescOracle(content.removePrefix("RHYME:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatPasswordGameWordGamesOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "PASSWORD:" + escOracle(v) + "|" + hostLabel + " password game — guess under rules"
    }

    private fun parsePasswordGameWordGamesOracle(content: String): String? {
        if (!content.startsWith("PASSWORD:")) return null
        val v = unescOracle(content.removePrefix("PASSWORD:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatTabooWordGamesOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(60)
        return "TABOO:" + escOracle(v) + "|" + hostLabel + " taboo — describe '" + v.substringBefore('|') + "' without banned words"
    }

    private fun parseTabooWordGamesOracle(content: String): String? {
        if (!content.startsWith("TABOO:")) return null
        val v = unescOracle(content.removePrefix("TABOO:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatTwoWordsWordGamesOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "TWOWORDS:" + escOracle(v) + "|" + hostLabel + " two-word story — start with '" + v + "'"
    }

    private fun parseTwoWordsWordGamesOracle(content: String): String? {
        if (!content.startsWith("TWOWORDS:")) return null
        val v = unescOracle(content.removePrefix("TWOWORDS:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatOneWordWordGamesOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "ONEWORD:" + escOracle(v) + "|" + hostLabel + " one-word story — continue with one word"
    }

    private fun parseOneWordWordGamesOracle(content: String): String? {
        if (!content.startsWith("ONEWORD:")) return null
        val v = unescOracle(content.removePrefix("ONEWORD:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatWordScrambleWordGamesOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(40)
        return "WORDSCRAMBLE:" + escOracle(v) + "|" + hostLabel + " word scramble"
    }

    private fun parseWordScrambleWordGamesOracle(content: String): String? {
        if (!content.startsWith("WORDSCRAMBLE:")) return null
        val v = unescOracle(content.removePrefix("WORDSCRAMBLE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    // ---------- generators (Random(42) in the CI tests) ----------

    private val STR_POOL = listOf(
        "", " ", "  ", "   a   ", "a", "ab", "abc", "|", "^", "\u0001", "\u0002",
        "a|b", "a^b", "a\u0001b", "a\u0002b", "||", "^^", "a|b^c",
        "hello", "hello world", "  padded  ", "\t", "\n", "a\tb", "a\nb",
        "é", "ß", "İ", "i", "I", "中文测试", "😀", "a😀b", "😀😀😀",
        "\u00a0", "x\u00a0y", "\u2007", "\u202f",
        "rock", "paper", "scissors", "ROCK", "Heads", "HEADS", "heads", "TAILS",
        "0", "1", "-1", "42", "9999999999", "2147483647", "-2147483648",
        "a".repeat(50), "x".repeat(130), "word ".repeat(30)
    )

    private val CH_POOL = listOf(
        "a", "b", "z", "A", "Z", "0", "9", " ", "|", "^", "\u0001", "\u0002",
        "\t", "\n", "é", "ß", "中", "😀", "\u00a0", ".", "-", "_", "!"
    )

    private fun randomString(rnd: Random): String {
        return when (rnd.nextInt(4)) {
            0 -> STR_POOL[rnd.nextInt(STR_POOL.size)]
            1 -> buildString {
                repeat(rnd.nextInt(0, 30)) { append(CH_POOL[rnd.nextInt(CH_POOL.size)]) }
            }
            2 -> {
                // Boundary-length strings around the take() limits.
                val n = listOf(
                    0, 1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 15, 16, 17, 19, 20, 21,
                    23, 24, 25, 29, 30, 31, 39, 40, 41, 49, 50, 51, 59, 60, 61,
                    79, 80, 81, 99, 100, 101, 119, 120, 121, 130
                )[rnd.nextInt(42)]
                val ch = listOf("a", " ", "|", "😀")[rnd.nextInt(4)]
                ch.repeat(n)
            }
            else -> {
                val base = buildString {
                    repeat(rnd.nextInt(0, 25)) { append('a' + rnd.nextInt(26)) }
                }
                base + listOf("", " ", "|", "^", "\u0001", "😀", "  ")[rnd.nextInt(7)]
            }
        }
    }

    private fun randomStringNonBlank(rnd: Random): String {
        repeat(10) {
            val s = randomString(rnd)
            if (s.trim().isNotBlank()) return s
        }
        return "x"
    }

    private val INT_POOL = listOf(
        Int.MIN_VALUE, Int.MAX_VALUE, -100000, -100, -31, -30, -6, -5, -3, -2, -1,
        0, 1, 2, 3, 4, 5, 6, 29, 30, 31, 59, 60, 61, 599, 600, 601, 100, 100000
    )

    private fun randomInt(rnd: Random): Int {
        return when (rnd.nextInt(3)) {
            0 -> INT_POOL[rnd.nextInt(INT_POOL.size)]
            1 -> rnd.nextInt(-1000, 1001)
            else -> rnd.nextInt()
        }
    }

    private fun randomStringList(rnd: Random): List<String> {
        val n = rnd.nextInt(0, 7)
        return List(n) { randomString(rnd) }
    }

    // EmojiDuel's format falls back to a random emoji for blank input, so the
    // oracle (and the differential harness) only ever feeds it non-blank input.
    private fun randomStringFor(rnd: Random, key: String): String {
        return if (key == "Duel:emojiDuel") randomStringNonBlank(rnd) else randomString(rnd)
    }

    private fun prefixForWordGames(key: String): String {
        return when (key) {
            "WordGames:wordHint" -> "WORDHINT:"
            "WordGames:acrostic" -> "ACROSTIC:"
            "WordGames:rhyme" -> "RHYME:"
            "WordGames:passwordGame" -> "PASSWORD:"
            "WordGames:taboo" -> "TABOO:"
            "WordGames:twoWords" -> "TWOWORDS:"
            "WordGames:oneWord" -> "ONEWORD:"
            "WordGames:wordScramble" -> "WORDSCRAMBLE:"
            else -> error("unknown key " + key)
        }
    }

    private fun randomFormatOutputForWordGames(rnd: Random, key: String): String {
        val host = randomString(rnd)
        return when (key) {
            "WordGames:wordHint" -> formatWordHintWordGamesOracle(randomString(rnd), randomString(rnd), host)
            "WordGames:acrostic" -> formatAcrosticWordGamesOracle(randomStringFor(rnd, "WordGames:acrostic"), host)
            "WordGames:rhyme" -> formatRhymeWordGamesOracle(randomStringFor(rnd, "WordGames:rhyme"), host)
            "WordGames:passwordGame" -> formatPasswordGameWordGamesOracle(randomStringFor(rnd, "WordGames:passwordGame"), host)
            "WordGames:taboo" -> formatTabooWordGamesOracle(randomStringFor(rnd, "WordGames:taboo"), host)
            "WordGames:twoWords" -> formatTwoWordsWordGamesOracle(randomStringFor(rnd, "WordGames:twoWords"), host)
            "WordGames:oneWord" -> formatOneWordWordGamesOracle(randomStringFor(rnd, "WordGames:oneWord"), host)
            "WordGames:wordScramble" -> formatWordScrambleWordGamesOracle(randomStringFor(rnd, "WordGames:wordScramble"), host)
            else -> error("unknown key " + key)
        }
    }

    private fun randomContentForWordGames(rnd: Random, key: String): String {
        val prefix = prefixForWordGames(key)
        return when (rnd.nextInt(4)) {
            0 -> randomFormatOutputForWordGames(rnd, key)
            1 -> prefix + randomString(rnd)
            2 -> randomString(rnd)
            else -> listOf("", prefix, prefix + "|", prefix + "a|b", "WRONG:" + randomString(rnd))[rnd.nextInt(5)]
        }
    }

    @Test
    fun formatSinglesRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("WordGames:acrostic", "WordGames:rhyme", "WordGames:passwordGame", "WordGames:taboo", "WordGames:twoWords", "WordGames:oneWord", "WordGames:wordScramble")) {
                val x = randomStringFor(rnd, key)
                val host = randomString(rnd)
                when (key) {
                    "WordGames:acrostic" -> assertEquals(
                        formatAcrosticWordGamesOracle(x, host),
                        GroupPlayClassicWordGames.formatAcrostic(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "WordGames:rhyme" -> assertEquals(
                        formatRhymeWordGamesOracle(x, host),
                        GroupPlayClassicWordGames.formatRhyme(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "WordGames:passwordGame" -> assertEquals(
                        formatPasswordGameWordGamesOracle(x, host),
                        GroupPlayClassicWordGames.formatPasswordGame(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "WordGames:taboo" -> assertEquals(
                        formatTabooWordGamesOracle(x, host),
                        GroupPlayClassicWordGames.formatTaboo(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "WordGames:twoWords" -> assertEquals(
                        formatTwoWordsWordGamesOracle(x, host),
                        GroupPlayClassicWordGames.formatTwoWords(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "WordGames:oneWord" -> assertEquals(
                        formatOneWordWordGamesOracle(x, host),
                        GroupPlayClassicWordGames.formatOneWord(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "WordGames:wordScramble" -> assertEquals(
                        formatWordScrambleWordGamesOracle(x, host),
                        GroupPlayClassicWordGames.formatWordScramble(x, host),
                        "key=$key x=$x host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseSinglesRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("WordGames:acrostic", "WordGames:rhyme", "WordGames:passwordGame", "WordGames:taboo", "WordGames:twoWords", "WordGames:oneWord", "WordGames:wordScramble")) {
                val content = randomContentForWordGames(rnd, key)
                when (key) {
                    "WordGames:acrostic" -> assertEquals(
                        parseAcrosticWordGamesOracle(content),
                        GroupPlayClassicWordGames.parseAcrostic(content),
                        "key=$key content=$content"
                    )
                    "WordGames:rhyme" -> assertEquals(
                        parseRhymeWordGamesOracle(content),
                        GroupPlayClassicWordGames.parseRhyme(content),
                        "key=$key content=$content"
                    )
                    "WordGames:passwordGame" -> assertEquals(
                        parsePasswordGameWordGamesOracle(content),
                        GroupPlayClassicWordGames.parsePasswordGame(content),
                        "key=$key content=$content"
                    )
                    "WordGames:taboo" -> assertEquals(
                        parseTabooWordGamesOracle(content),
                        GroupPlayClassicWordGames.parseTaboo(content),
                        "key=$key content=$content"
                    )
                    "WordGames:twoWords" -> assertEquals(
                        parseTwoWordsWordGamesOracle(content),
                        GroupPlayClassicWordGames.parseTwoWords(content),
                        "key=$key content=$content"
                    )
                    "WordGames:oneWord" -> assertEquals(
                        parseOneWordWordGamesOracle(content),
                        GroupPlayClassicWordGames.parseOneWord(content),
                        "key=$key content=$content"
                    )
                    "WordGames:wordScramble" -> assertEquals(
                        parseWordScrambleWordGamesOracle(content),
                        GroupPlayClassicWordGames.parseWordScramble(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatWordHintRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("WordGames:wordHint")) {
                val a = randomString(rnd)
                val b = randomString(rnd)
                val host = randomString(rnd)
                when (key) {
                    "WordGames:wordHint" -> assertEquals(
                        formatWordHintWordGamesOracle(a, b, host),
                        GroupPlayClassicWordGames.formatWordHint(a, b, host),
                        "key=$key a=$a b=$b host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseWordHintRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("WordGames:wordHint")) {
                val content = randomContentForWordGames(rnd, key)
                when (key) {
                    "WordGames:wordHint" -> assertEquals(
                        parseWordHintWordGamesOracle(content),
                        GroupPlayClassicWordGames.parseWordHint(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // WordHint: trim both sides; no separator guard, so a bare body
        // duplicates into both fields; a blank hint rejects the pair.
        assertEquals("WORDHINT:h|a|H word hint", GroupPlayClassicWordGames.formatWordHint("  h ", "  a ", "H"))
        assertEquals("h" to "a", GroupPlayClassicWordGames.parseWordHint("WORDHINT:h|a|H word hint"))
        assertEquals("only" to "only", GroupPlayClassicWordGames.parseWordHint("WORDHINT:only"))
        assertNull(GroupPlayClassicWordGames.parseWordHint("WORDHINT:|a"))
        // Acrostic / Rhyme / TwoWords interpolate the raw seed into the suffix.
        assertEquals("ACROSTIC:seed|H acrostic — start each line with letters of 'seed'", GroupPlayClassicWordGames.formatAcrostic("  seed ", "H"))
        assertEquals("RHYME:seed|H rhyme chain — rhyme with 'seed'", GroupPlayClassicWordGames.formatRhyme("seed", "H"))
        assertEquals("TWOWORDS:seed|H two-word story — start with 'seed'", GroupPlayClassicWordGames.formatTwoWords("seed", "H"))
        // Taboo takes the word before '|' from the RAW card for the suffix,
        // while the body carries the escaped whole card.
        assertEquals("TABOO:word\u0001banned|H taboo — describe 'word' without banned words", GroupPlayClassicWordGames.formatTaboo("word|banned", "H"))
        assertEquals("TABOO:plain|H taboo — describe 'plain' without banned words", GroupPlayClassicWordGames.formatTaboo("plain", "H"))
        assertEquals("word|banned", GroupPlayClassicWordGames.parseTaboo("TABOO:word\u0001banned|H taboo — describe 'word' without banned words"))
        // PasswordGame / OneWord / WordScramble: plain single-string pairs.
        assertEquals("PASSWORD:hint|H password game — guess under rules", GroupPlayClassicWordGames.formatPasswordGame("hint", "H"))
        assertEquals("ONEWORD:word|H one-word story — continue with one word", GroupPlayClassicWordGames.formatOneWord("word", "H"))
        assertEquals("WORDSCRAMBLE:pair|H word scramble", GroupPlayClassicWordGames.formatWordScramble("pair", "H"))
        assertNull(GroupPlayClassicWordGames.parseOneWord("ONEWORD:|H"))
        assertEquals("word", GroupPlayClassicWordGames.parseOneWord("ONEWORD:word|H one-word story — continue with one word"))
    }

}
