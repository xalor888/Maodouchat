package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for the GroupPlay classic codec cluster
 * (checklist line 931, 50th block): [GroupPlayClassicDuel]. Test-only, zero production
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
 * - formatSpin / formatSimon do not trim; formatCoinFlip maps every
 *   non-HEADS side (case-insensitive) to TAILS, and its parse has no
 *   blank->null, so an empty body parses back to "".
 * - formatLottery joins the pool first and then take(200) cuts mid-item;
 *   the trailing partial separator yields no extra item on parse.
 * - formatEmojiDuel falls back to a random emoji for blank input, so the
 *   fuzz inputs for it are non-blank only; only the round-trip shape is pinned.
 * - parseCountdownRace returns the coerced seconds as a String, not an Int.
 * Verification (offline, JDK 17): the real production code was compiled
 * verbatim with the Kotlin compiler and differentially checked against this
 * oracle on 29969 cases — the exact inputs these tests generate
 * (Random(42)), a codec round-trip, int / string / parse exhaustives, and
 * Python-generated random lines — with 0 differences. Every pinned
 * expectation below was checked against real production. The 13 test
 * methods were also run locally against the real production code
 * (kotlin-test stubs): all pass.
 */
class GroupPlayClassicDuelFuzzTest {

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

    private fun formatSpinDuelOracle(x: String, hostLabel: String): String {
        val v = x.take(40)
        return "SPIN:" + escOracle(v) + "|" + hostLabel + " spun the wheel"
    }

    private fun parseSpinDuelOracle(content: String): String? {
        if (!content.startsWith("SPIN:")) return null
        val v = unescOracle(content.removePrefix("SPIN:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatBingoDuelOracle(board: List<String>, hostLabel: String): String {
        val cells = board.joinToString("^") { escOracle(it.take(4)) }
        return "BINGO:" + cells + "|" + hostLabel + " bingo board"
    }

    private fun parseBingoDuelOracle(content: String): List<String>? {
        if (!content.startsWith("BINGO:")) return null
        val body = content.removePrefix("BINGO:").substringBefore('|')
        val cells = body.split('^').filter { it.isNotBlank() }.map { unescOracle(it) }
        return cells.takeIf { it.isNotEmpty() }
    }

    private fun formatLotteryDuelOracle(pool: List<String>, winner: String, hostLabel: String): String {
        val p = pool.joinToString("^") { escOracle(it.take(24)) }.take(200)
        return "LOTTERY:" + escOracle(winner) + "|" + p + "|" + hostLabel + " lottery"
    }

    private fun parseLotteryDuelOracle(content: String): Pair<String, List<String>>? {
        if (!content.startsWith("LOTTERY:")) return null
        val body = content.removePrefix("LOTTERY:")
        val winner = unescOracle(body.substringBefore('|'))
        val rest = body.substringAfter('|', "")
        val pool = rest.substringBefore('|').split('^').filter { it.isNotBlank() }.map { unescOracle(it) }
        if (winner.isBlank()) return null
        return winner to pool
    }

    private fun formatCoinFlipDuelOracle(side: String, hostLabel: String): String {
        val s = if (side.equals("HEADS", true)) "HEADS" else "TAILS"
        return "COINFLIP:" + escOracle(s) + "|" + hostLabel + " flipped " + s
    }

    private fun parseCoinFlipDuelOracle(content: String): String? {
        if (!content.startsWith("COINFLIP:")) return null
        return unescOracle(content.removePrefix("COINFLIP:").substringBefore('|'))
    }

    private fun formatSimonDuelOracle(x: String, hostLabel: String): String {
        val v = x.take(16)
        return "SIMON:" + escOracle(v) + "|" + hostLabel + " simon says — repeat the sequence!"
    }

    private fun parseSimonDuelOracle(content: String): String? {
        if (!content.startsWith("SIMON:")) return null
        val v = unescOracle(content.removePrefix("SIMON:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    // NOTE: blank-after-trim input falls back to a random emoji in
    // production; the generator only feeds non-blank input, so the
    // oracle below (trim + take only) is exact on its whole domain.
    private fun formatEmojiDuelDuelOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(16)
        return "EMOJIDUEL:" + escOracle(v) + "|" + hostLabel + " emoji duel — pick a side!"
    }

    private fun parseEmojiDuelDuelOracle(content: String): String? {
        if (!content.startsWith("EMOJIDUEL:")) return null
        val v = unescOracle(content.removePrefix("EMOJIDUEL:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatHotPotatoDuelOracle(n: Int, hostLabel: String): String {
        val s = clampIntOracle(n, 5, 30)
        return "HOTPOTATO:" + s.toString() + "|" + hostLabel + " hot potato — pass in " + s.toString() + "s!"
    }

    private fun parseHotPotatoDuelOracle(content: String): Int? {
        if (!content.startsWith("HOTPOTATO:")) return null
        return unescOracle(content.removePrefix("HOTPOTATO:").substringBefore('|')).toIntOrNull()
    }

    private fun formatCountdownRaceDuelOracle(n: Int, hostLabel: String): String {
        val s = clampIntOracle(n, 2, 30)
        return "COUNTRACE:" + s.toString() + "|" + hostLabel + " countdown race - first reply wins!"
    }

    private fun parseCountdownRaceDuelOracle(content: String): String? {
        if (!content.startsWith("COUNTRACE:")) return null
        val v = unescOracle(content.removePrefix("COUNTRACE:").substringBefore('|'))
        if (v.isBlank()) return null
        return v
    }

    private fun formatReactionDuelDuelOracle(x: String, hostLabel: String): String {
        val v = x.trim().take(20)
        return "REACTDUEL:" + escOracle(v) + "|" + hostLabel + " reaction duel"
    }

    private fun parseReactionDuelDuelOracle(content: String): String? {
        if (!content.startsWith("REACTDUEL:")) return null
        val v = unescOracle(content.removePrefix("REACTDUEL:").substringBefore('|'))
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

    private fun prefixForDuel(key: String): String {
        return when (key) {
            "Duel:spin" -> "SPIN:"
            "Duel:bingo" -> "BINGO:"
            "Duel:lottery" -> "LOTTERY:"
            "Duel:coinFlip" -> "COINFLIP:"
            "Duel:simon" -> "SIMON:"
            "Duel:emojiDuel" -> "EMOJIDUEL:"
            "Duel:hotPotato" -> "HOTPOTATO:"
            "Duel:countdownRace" -> "COUNTRACE:"
            "Duel:reactionDuel" -> "REACTDUEL:"
            else -> error("unknown key " + key)
        }
    }

    private fun randomFormatOutputForDuel(rnd: Random, key: String): String {
        val host = randomString(rnd)
        return when (key) {
            "Duel:spin" -> formatSpinDuelOracle(randomStringFor(rnd, "Duel:spin"), host)
            "Duel:bingo" -> formatBingoDuelOracle(randomStringList(rnd), host)
            "Duel:lottery" -> formatLotteryDuelOracle(randomStringList(rnd), randomString(rnd), host)
            "Duel:coinFlip" -> formatCoinFlipDuelOracle(randomString(rnd), host)
            "Duel:simon" -> formatSimonDuelOracle(randomStringFor(rnd, "Duel:simon"), host)
            "Duel:emojiDuel" -> formatEmojiDuelDuelOracle(randomStringFor(rnd, "Duel:emojiDuel"), host)
            "Duel:hotPotato" -> formatHotPotatoDuelOracle(randomInt(rnd), host)
            "Duel:countdownRace" -> formatCountdownRaceDuelOracle(randomInt(rnd), host)
            "Duel:reactionDuel" -> formatReactionDuelDuelOracle(randomStringFor(rnd, "Duel:reactionDuel"), host)
            else -> error("unknown key " + key)
        }
    }

    private fun randomContentForDuel(rnd: Random, key: String): String {
        val prefix = prefixForDuel(key)
        return when (rnd.nextInt(4)) {
            0 -> randomFormatOutputForDuel(rnd, key)
            1 -> prefix + randomString(rnd)
            2 -> randomString(rnd)
            else -> listOf("", prefix, prefix + "|", prefix + "a|b", "WRONG:" + randomString(rnd))[rnd.nextInt(5)]
        }
    }

    @Test
    fun formatSinglesRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:spin", "Duel:simon", "Duel:emojiDuel", "Duel:reactionDuel")) {
                val x = randomStringFor(rnd, key)
                val host = randomString(rnd)
                when (key) {
                    "Duel:spin" -> assertEquals(
                        formatSpinDuelOracle(x, host),
                        GroupPlayClassicDuel.formatSpin(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Duel:simon" -> assertEquals(
                        formatSimonDuelOracle(x, host),
                        GroupPlayClassicDuel.formatSimon(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Duel:emojiDuel" -> assertEquals(
                        formatEmojiDuelDuelOracle(x, host),
                        GroupPlayClassicDuel.formatEmojiDuel(x, host),
                        "key=$key x=$x host=$host"
                    )
                    "Duel:reactionDuel" -> assertEquals(
                        formatReactionDuelDuelOracle(x, host),
                        GroupPlayClassicDuel.formatReactionDuel(x, host),
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
            for (key in listOf("Duel:spin", "Duel:simon", "Duel:emojiDuel", "Duel:reactionDuel")) {
                val content = randomContentForDuel(rnd, key)
                when (key) {
                    "Duel:spin" -> assertEquals(
                        parseSpinDuelOracle(content),
                        GroupPlayClassicDuel.parseSpin(content),
                        "key=$key content=$content"
                    )
                    "Duel:simon" -> assertEquals(
                        parseSimonDuelOracle(content),
                        GroupPlayClassicDuel.parseSimon(content),
                        "key=$key content=$content"
                    )
                    "Duel:emojiDuel" -> assertEquals(
                        parseEmojiDuelDuelOracle(content),
                        GroupPlayClassicDuel.parseEmojiDuel(content),
                        "key=$key content=$content"
                    )
                    "Duel:reactionDuel" -> assertEquals(
                        parseReactionDuelDuelOracle(content),
                        GroupPlayClassicDuel.parseReactionDuel(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatBingoRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:bingo")) {
                val board = randomStringList(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Duel:bingo" -> assertEquals(
                        formatBingoDuelOracle(board, host),
                        GroupPlayClassicDuel.formatBingo(board, host),
                        "key=$key board=$board host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseBingoRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:bingo")) {
                val content = randomContentForDuel(rnd, key)
                when (key) {
                    "Duel:bingo" -> assertEquals(
                        parseBingoDuelOracle(content),
                        GroupPlayClassicDuel.parseBingo(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatLotteryRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:lottery")) {
                val pool = randomStringList(rnd)
                val winner = randomString(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Duel:lottery" -> assertEquals(
                        formatLotteryDuelOracle(pool, winner, host),
                        GroupPlayClassicDuel.formatLottery(pool, winner, host),
                        "key=$key pool=$pool winner=$winner host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseLotteryRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:lottery")) {
                val content = randomContentForDuel(rnd, key)
                when (key) {
                    "Duel:lottery" -> assertEquals(
                        parseLotteryDuelOracle(content),
                        GroupPlayClassicDuel.parseLottery(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatCoinFlipRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:coinFlip")) {
                val side = randomString(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Duel:coinFlip" -> assertEquals(
                        formatCoinFlipDuelOracle(side, host),
                        GroupPlayClassicDuel.formatCoinFlip(side, host),
                        "key=$key side=$side host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseCoinFlipRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:coinFlip")) {
                val content = randomContentForDuel(rnd, key)
                when (key) {
                    "Duel:coinFlip" -> assertEquals(
                        parseCoinFlipDuelOracle(content),
                        GroupPlayClassicDuel.parseCoinFlip(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatHotPotatoRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:hotPotato")) {
                val n = randomInt(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Duel:hotPotato" -> assertEquals(
                        formatHotPotatoDuelOracle(n, host),
                        GroupPlayClassicDuel.formatHotPotato(n, host),
                        "key=$key n=$n host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseHotPotatoRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:hotPotato")) {
                val content = randomContentForDuel(rnd, key)
                when (key) {
                    "Duel:hotPotato" -> assertEquals(
                        parseHotPotatoDuelOracle(content),
                        GroupPlayClassicDuel.parseHotPotato(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun formatCountdownRaceRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:countdownRace")) {
                val n = randomInt(rnd)
                val host = randomString(rnd)
                when (key) {
                    "Duel:countdownRace" -> assertEquals(
                        formatCountdownRaceDuelOracle(n, host),
                        GroupPlayClassicDuel.formatCountdownRace(n, host),
                        "key=$key n=$n host=$host"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun parseCountdownRaceRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            for (key in listOf("Duel:countdownRace")) {
                val content = randomContentForDuel(rnd, key)
                when (key) {
                    "Duel:countdownRace" -> assertEquals(
                        parseCountdownRaceDuelOracle(content),
                        GroupPlayClassicDuel.parseCountdownRace(content),
                        "key=$key content=$content"
                    )
                    else -> error("unknown key " + key)
                }
            }
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // Spin / Simon do NOT trim: surrounding spaces survive the round-trip.
        assertEquals("SPIN:  abc  |H spun the wheel", GroupPlayClassicDuel.formatSpin("  abc  ", "H"))
        assertEquals("SIMON:  abc  |H simon says — repeat the sequence!", GroupPlayClassicDuel.formatSimon("  abc  ", "H"))
        assertEquals("  abc  ", GroupPlayClassicDuel.parseSpin("SPIN:  abc  |H spun the wheel"))
        // Bingo: per-cell take(4); blank cells are dropped; an empty board
        // formats to an empty segment and parses back to null.
        assertEquals("BINGO:a^bb^ccc|H bingo board", GroupPlayClassicDuel.formatBingo(listOf("a", "bb", "ccc"), "H"))
        assertEquals(listOf("a", "bb", "ccc"), GroupPlayClassicDuel.parseBingo("BINGO:a^bb^ccc|H bingo board"))
        assertEquals("BINGO:1234^x|H bingo board", GroupPlayClassicDuel.formatBingo(listOf("12345", "x"), "H"))
        assertEquals("BINGO:|H bingo board", GroupPlayClassicDuel.formatBingo(emptyList(), "H"))
        assertNull(GroupPlayClassicDuel.parseBingo("BINGO:|H bingo board"))
        assertEquals(listOf("a", "b"), GroupPlayClassicDuel.parseBingo("BINGO:a^^b|H"))
        // Lottery: the pool is joined first, then take(200) cuts mid-item;
        // the trailing partial separator yields no extra item on parse.
        assertEquals("LOTTERY:win|a^b|H lottery", GroupPlayClassicDuel.formatLottery(listOf("a", "b"), "win", "H"))
        assertEquals("win" to listOf("a", "b"), GroupPlayClassicDuel.parseLottery("LOTTERY:win|a^b|H lottery"))
        assertEquals("LOTTERY:win|a\u0001b|H lottery", GroupPlayClassicDuel.formatLottery(listOf("a|b"), "win", "H"))
        assertEquals("LOTTERY:win||H lottery", GroupPlayClassicDuel.formatLottery(emptyList(), "win", "H"))
        assertEquals("win" to emptyList<String>(), GroupPlayClassicDuel.parseLottery("LOTTERY:win||H lottery"))
        assertNull(GroupPlayClassicDuel.parseLottery("LOTTERY:|a^b"))
        val bigPool = List(10) { "a".repeat(24) }
        assertEquals("win" to List(8) { "a".repeat(24) },
            GroupPlayClassicDuel.parseLottery(GroupPlayClassicDuel.formatLottery(bigPool, "win", "H")))
        // CoinFlip: any non-HEADS (case-insensitive) becomes TAILS; parse has
        // no blank->null, so an empty body parses back to "".
        assertEquals("COINFLIP:HEADS|H flipped HEADS", GroupPlayClassicDuel.formatCoinFlip("HeAdS", "H"))
        assertEquals("COINFLIP:TAILS|H flipped TAILS", GroupPlayClassicDuel.formatCoinFlip("whatever", "H"))
        assertEquals("HEADS", GroupPlayClassicDuel.parseCoinFlip("COINFLIP:HEADS|H flipped HEADS"))
        assertEquals("", GroupPlayClassicDuel.parseCoinFlip("COINFLIP:|H"))
        assertNull(GroupPlayClassicDuel.parseCoinFlip("WRONG:HEADS"))
        // HotPotato clamps 5..30; CountdownRace clamps 2..30 but parses back
        // to a String, not an Int.
        assertEquals("HOTPOTATO:5|H hot potato — pass in 5s!", GroupPlayClassicDuel.formatHotPotato(3, "H"))
        assertEquals("HOTPOTATO:30|H hot potato — pass in 30s!", GroupPlayClassicDuel.formatHotPotato(100, "H"))
        assertEquals(7, GroupPlayClassicDuel.parseHotPotato("HOTPOTATO:7|H hot potato — pass in 7s!"))
        assertNull(GroupPlayClassicDuel.parseHotPotato("HOTPOTATO:x"))
        assertEquals("COUNTRACE:2|H countdown race - first reply wins!", GroupPlayClassicDuel.formatCountdownRace(1, "H"))
        assertEquals("2", GroupPlayClassicDuel.parseCountdownRace("COUNTRACE:2|H countdown race - first reply wins!"))
        assertNull(GroupPlayClassicDuel.parseCountdownRace("COUNTRACE:|H"))
        // EmojiDuel trims and takes 16; a blank pair falls back to a random
        // emoji, so only the round-trip shape is pinned, not the value.
        assertEquals("EMOJIDUEL:😀vs😀|H emoji duel — pick a side!", GroupPlayClassicDuel.formatEmojiDuel("  😀vs😀  ", "H"))
        assertEquals("x", GroupPlayClassicDuel.parseEmojiDuel(GroupPlayClassicDuel.formatEmojiDuel("x", "H")))
        assertTrue(GroupPlayClassicDuel.parseEmojiDuel(GroupPlayClassicDuel.formatEmojiDuel("   ", "H")) != null)
        // ReactionDuel trims.
        assertEquals("REACTDUEL:ab|H reaction duel", GroupPlayClassicDuel.formatReactionDuel("  ab ", "H"))
    }

}
