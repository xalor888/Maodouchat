package com.maodouchat.domain.groups

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：驱动与测试共用，串里出现分隔符也不怕。
private fun encStr195a(s: String): String {
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

private fun decStr195a(h: String): String {
    if (h.isEmpty()) return ""
    val chars = CharArray(h.length / 4) { h.substring(it * 4, it * 4 + 4).toInt(16).toChar() }
    return String(chars)
}

private fun encOpt195a(s: String?): String = if (s == null) "NULL" else "S:" + encStr195a(s)

private val RPS_CHOICES195a = listOf("rock", "paper", "scissors")

private fun escOracle195a(s: String): String = s.replace("|", "\u0001").replace("^", "\u0002")
private fun unescOracle195a(s: String): String = s.replace("\u0001", "|").replace("\u0002", "^")

// formatRps 的独立重实现。无效选项生产走真随机 rollRps，值不可复现，
// 所以差分输入只含有效选项；oracle 在此分支固定回落为 rock（仅作占位）。
private fun formatRpsOracle195a(choice: String, userLabel: String): String {
    val c = choice.lowercase().let { if (it in RPS_CHOICES195a) it else "rock" }
    val emoji = when (c) {
        "rock" -> "[R]"
        "paper" -> "[P]"
        else -> "[S]"
    }
    return "RPS:${escOracle195a(c)}|$userLabel played $emoji $c"
}

private fun parseRpsOracle195a(content: String): String? {
    if (!content.startsWith("RPS:")) return null
    return unescOracle195a(content.removePrefix("RPS:").substringBefore('|').trim()).ifBlank { null }
}

private fun formatNumberBombOracle195a(secret: Int, max: Int, hostLabel: String): String =
    "BOMB:$max:$secret|$hostLabel started number bomb (1-$max)"

private fun parseNumberBombOracle195a(content: String): Triple<Int, Int, String>? {
    if (!content.startsWith("BOMB:")) return null
    val body = content.removePrefix("BOMB:")
    val head = unescOracle195a(body.substringBefore('|'))
    val parts = head.split(':')
    if (parts.size < 2) return null
    val max = parts[0].toIntOrNull() ?: return null
    val secret = parts[1].toIntOrNull() ?: return null
    return Triple(max, secret, body.substringAfter('|', ""))
}

private fun formatReactionRaceOracle195a(token: String, hostLabel: String): String {
    // 空 token 生产走真随机 randomRaceToken，差分输入只含非空 token；
    // oracle 在此分支固定用 "blank" 占位。
    val t = token.ifBlank { "blank" }.take(16)
    return "RACE:${escOracle195a(t)}|$hostLabel reaction race: first to reply with $t wins"
}

private fun parseReactionRaceOracle195a(content: String): Pair<String, String>? {
    if (!content.startsWith("RACE:")) return null
    val body = content.removePrefix("RACE:")
    val token = unescOracle195a(body.substringBefore('|')).ifBlank { return null }
    return token to body.substringAfter('|', "")
}

private fun formatRpsProd195a(choice: String, userLabel: String): String =
    GroupPkPolicy.formatRps(choice, userLabel)

private fun parseRpsProd195a(content: String): String? = GroupPkPolicy.parseRps(content)

private fun formatNumberBombProd195a(secret: Int, max: Int, hostLabel: String): String =
    GroupPkPolicy.formatNumberBomb(secret, max, hostLabel)

private fun parseNumberBombProd195a(content: String): Triple<Int, Int, String>? =
    GroupPkPolicy.parseNumberBomb(content)

private fun formatReactionRaceProd195a(token: String, hostLabel: String): String =
    GroupPkPolicy.formatReactionRace(token, hostLabel)

private fun parseReactionRaceProd195a(content: String): Pair<String, String>? =
    GroupPkPolicy.parseReactionRace(content)

// op: rps | prps | bomb | pbomb | race | prace；bomb 用 n1=secret、n2=max。
private data class PkCase195a(
    val op: String,
    val s1: String = "",
    val s2: String = "",
    val n1: Int = 0,
    val n2: Int = 0,
)

private fun pkOracle195a(c: PkCase195a): String = when (c.op) {
    "rps" -> "S:" + encStr195a(formatRpsOracle195a(c.s1, c.s2))
    "prps" -> encOpt195a(parseRpsOracle195a(c.s1))
    "bomb" -> "S:" + encStr195a(formatNumberBombOracle195a(c.n1, c.n2, c.s1))
    "pbomb" -> parseNumberBombOracle195a(c.s1)?.let { (max, secret, label) ->
        "$max|$secret|" + encStr195a(label)
    } ?: "NULL"
    "race" -> "S:" + encStr195a(formatReactionRaceOracle195a(c.s1, c.s2))
    "prace" -> parseReactionRaceOracle195a(c.s1)?.let { (token, rest) ->
        encStr195a(token) + "|" + encStr195a(rest)
    } ?: "NULL"
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private fun pkProd195a(c: PkCase195a): String = when (c.op) {
    "rps" -> "S:" + encStr195a(formatRpsProd195a(c.s1, c.s2))
    "prps" -> encOpt195a(parseRpsProd195a(c.s1))
    "bomb" -> "S:" + encStr195a(formatNumberBombProd195a(c.n1, c.n2, c.s1))
    "pbomb" -> parseNumberBombProd195a(c.s1)?.let { (max, secret, label) ->
        "$max|$secret|" + encStr195a(label)
    } ?: "NULL"
    "race" -> "S:" + encStr195a(formatReactionRaceProd195a(c.s1, c.s2))
    "prace" -> parseReactionRaceProd195a(c.s1)?.let { (token, rest) ->
        encStr195a(token) + "|" + encStr195a(rest)
    } ?: "NULL"
    else -> throw IllegalArgumentException("unknown op ${c.op}")
}

private val RPS_VALID_CHOICES195a = listOf(
    "rock", "paper", "scissors", "ROCK", "Rock", "PAPER", "Paper",
    "SCISSORS", "Scissors", "rOcK", "pApEr", "sCiSsOrS"
)

private val PK_LABELS195a = listOf(
    "", " ", "Alice", "Bob", "a|b", "x^y", "|", "^", "\u0001", "\u0002",
    "a\u0001b^c", "\u00e9", "\ud83d\ude00", "a b", "x".repeat(40),
    "RPS:rock", "BOMB:1:2", "RACE:t", "\n", "\t", "a\nb", "  ",
    "Alice played [R] rock", "a:b", "a,b", "a;b", "a?b", "a=b", "%s", "{}"
)

private val PK_RANDOM_CHARS195a = listOf(
    "a", "b", "c", "X", "Y", "Z", "0", "1", "9", " ", "_", "-", ".",
    ":", "|", "^", "/", "\n", "\t", "\u00e9", "\ud83d\ude00", "\u0001", "\u0002",
    "<", "R", "P", "S", "B", "O", "M", ",", ";", "=", "?", "&", "%", "#",
    "@", "!", "~", "*", "'", "\"", "\\", "(", ")", "[", "]", "{", "}", "$", "`"
)

private val PK_BOMB_NUMS195a = listOf(
    "100", "42", "0", "-1", "+5", "05", " 5", "5 ", "", "abc", "1.5",
    "2147483647", "2147483648", "-2147483648", "-2147483649", "0x10", "1e3",
    "１２３", "99999999999999999999", "10", "1", "2", "007", "-0", "+0"
)

private val PK_BOMB_INTS195a = listOf(
    0, 1, 2, 10, 42, 99, 100, 1000, -1, -5, -100, Int.MAX_VALUE, Int.MIN_VALUE
)

private fun randomPkStr195a(r: Random, maxLen: Int = 24): String =
    (0 until r.nextInt(maxLen + 1)).joinToString("") { PK_RANDOM_CHARS195a[r.nextInt(PK_RANDOM_CHARS195a.size)] }

private fun randomRpsChoice195a(r: Random): String {
    if (r.nextInt(3) == 0) return RPS_VALID_CHOICES195a[r.nextInt(RPS_VALID_CHOICES195a.size)]
    val base = RPS_CHOICES195a[r.nextInt(RPS_CHOICES195a.size)]
    return base.map { if (r.nextBoolean()) it.uppercaseChar() else it }.joinToString("")
}

// 按码点变异：先拆成码点再改，星号字符不会被拆成孤代理。
private fun mutatePkStr195a(r: Random, s: String): String {
    val cps = s.codePoints().toArray().toMutableList()
    repeat(r.nextInt(0, 4)) {
        if (cps.isEmpty()) {
            cps.add(PK_RANDOM_CHARS195a[r.nextInt(PK_RANDOM_CHARS195a.size)].codePoints().toArray()[0])
            return@repeat
        }
        when (r.nextInt(4)) {
            0 -> cps[r.nextInt(cps.size)] = PK_RANDOM_CHARS195a[r.nextInt(PK_RANDOM_CHARS195a.size)].codePoints().toArray()[0]
            1 -> cps.removeAt(r.nextInt(cps.size))
            2 -> cps.add(r.nextInt(cps.size + 1), PK_RANDOM_CHARS195a[r.nextInt(PK_RANDOM_CHARS195a.size)].codePoints().toArray()[0])
            else -> {
                val cut = r.nextInt(cps.size + 1)
                while (cps.size > cut) cps.removeAt(cps.size - 1)
            }
        }
    }
    val arr = cps.toIntArray()
    return String(arr, 0, arr.size)
}

private fun structuredRps195a(r: Random): String =
    formatRpsOracle195a(randomRpsChoice195a(r), PK_LABELS195a[r.nextInt(PK_LABELS195a.size)])

private fun structuredBomb195a(r: Random): String {
    val max = PK_BOMB_NUMS195a[r.nextInt(PK_BOMB_NUMS195a.size)]
    val secret = PK_BOMB_NUMS195a[r.nextInt(PK_BOMB_NUMS195a.size)]
    val label = if (r.nextInt(3) == 0) randomPkStr195a(r) else PK_LABELS195a[r.nextInt(PK_LABELS195a.size)]
    return "BOMB:$max:$secret|$label"
}

private fun structuredRace195a(r: Random): String {
    val token = when (r.nextInt(4)) {
        0 -> "\ud83d\ude80"
        1 -> "a|b"
        2 -> "x^y"
        else -> randomPkStr195a(r, 12).ifBlank { "t" }
    }
    val rest = if (r.nextInt(3) == 0) randomPkStr195a(r) else PK_LABELS195a[r.nextInt(PK_LABELS195a.size)]
    return "RACE:${escOracle195a(token)}|$rest"
}

private fun randomPkCase195a(r: Random): PkCase195a = when (r.nextInt(6)) {
    0 -> PkCase195a("rps", randomRpsChoice195a(r), PK_LABELS195a[r.nextInt(PK_LABELS195a.size)])
    1 -> PkCase195a("prps", if (r.nextInt(2) == 0) structuredRps195a(r) else mutatePkStr195a(r, structuredRps195a(r)))
    2 -> PkCase195a("bomb", PK_LABELS195a[r.nextInt(PK_LABELS195a.size)], "", PK_BOMB_INTS195a[r.nextInt(PK_BOMB_INTS195a.size)], PK_BOMB_INTS195a[r.nextInt(PK_BOMB_INTS195a.size)])
    3 -> PkCase195a("pbomb", if (r.nextInt(2) == 0) structuredBomb195a(r) else mutatePkStr195a(r, structuredBomb195a(r)))
    4 -> PkCase195a("race", randomPkStr195a(r, 20).ifBlank { "t" }, PK_LABELS195a[r.nextInt(PK_LABELS195a.size)])
    else -> PkCase195a("prace", if (r.nextInt(2) == 0) structuredRace195a(r) else mutatePkStr195a(r, structuredRace195a(r)))
}

private val PK_BOUNDARY_CONTENTS195a = listOf(
    "RPS:", "RPS:rock", "RPS:rock|", "RPS:|x", "RPS:rock|x|y", "rps:rock|x",
    "RPS:Rock|x", "RPS: ROCK|x", "RPS:rock |x", "RPS:\u0001|x", "RPS:\u0002|x",
    "RPS:rock|x\u0001y", "RPS:  rock  |x", "RPS:\t|x",
    "BOMB:", "BOMB:100:42", "BOMB:100:42|", "BOMB:|:x", "BOMB:100:42|x|y",
    "BOMB:100:42:7|x", "bomb:100:42|x", "BOMB: 100 : 42 |x", "BOMB:+100:+42|x",
    "BOMB:0100:0042|x", "BOMB:2147483648:1|x", "BOMB:-2147483649:1|x",
    "RACE:", "RACE:\ud83d\ude80", "RACE:\ud83d\ude80|", "RACE:|x", "RACE:\ud83d\ude80|x|y",
    "race:\ud83d\ude80|x", "RACE:\u0001|x", "RACE:\u0002|x", "RACE:aaaaaaaaaaaaaaaaa|x",
    "RACE:aaaaaaaaaaaaaaaa|x", "RACE: |x", "RACE:\t|x"
)

private fun boundaryPkCase195a(r: Random): PkCase195a = when (r.nextInt(6)) {
    0 -> PkCase195a("rps", RPS_VALID_CHOICES195a[r.nextInt(RPS_VALID_CHOICES195a.size)], PK_LABELS195a[r.nextInt(PK_LABELS195a.size)])
    1 -> PkCase195a("prps", PK_BOUNDARY_CONTENTS195a[r.nextInt(PK_BOUNDARY_CONTENTS195a.size)])
    2 -> PkCase195a("bomb", PK_LABELS195a[r.nextInt(PK_LABELS195a.size)], "", PK_BOMB_INTS195a[r.nextInt(PK_BOMB_INTS195a.size)], PK_BOMB_INTS195a[r.nextInt(PK_BOMB_INTS195a.size)])
    3 -> PkCase195a("pbomb", PK_BOUNDARY_CONTENTS195a[r.nextInt(PK_BOUNDARY_CONTENTS195a.size)])
    4 -> PkCase195a("race", listOf("\ud83d\ude80", "\u26a1", "a", "a|b", "x^y", "a".repeat(16), "a".repeat(17), "ab", "\u0001", "\u0002", "a b", "a:b")[r.nextInt(12)], PK_LABELS195a[r.nextInt(PK_LABELS195a.size)])
    else -> PkCase195a("prace", PK_BOUNDARY_CONTENTS195a[r.nextInt(PK_BOUNDARY_CONTENTS195a.size)])
}

// ---------- tests ----------

class GroupPkPolicyFuzzTest {

    @Test
    fun pkDifferential195() {
        val r = Random(195001)
        repeat(300) {
            val c = randomPkCase195a(r)
            assertEquals(pkOracle195a(c), pkProd195a(c))
        }
    }

    @Test
    fun pkBoundaryDifferential195() {
        val r = Random(195002)
        repeat(300) {
            val c = boundaryPkCase195a(r)
            assertEquals(pkOracle195a(c), pkProd195a(c))
        }
    }

    @Test
    fun pkPinned195() {
        // format/parse 逐字核对（与主分支 GroupPkPolicyTest 的固定用例一致）。
        assertEquals("RPS:rock|Alice played [R] rock", formatRpsOracle195a("rock", "Alice"))
        assertEquals("RPS:rock|Alice played [R] rock", formatRpsProd195a("rock", "Alice"))
        assertEquals("RPS:paper|Bob played [P] paper", formatRpsProd195a("PAPER", "Bob"))
        assertEquals("RPS:scissors|C played [S] scissors", formatRpsProd195a("Scissors", "C"))
        assertEquals("rock", parseRpsProd195a("RPS:rock|Alice played [R] rock"))
        assertEquals("paper", parseRpsProd195a("RPS:paper|Bob played [P] paper"))
        assertEquals("scissors", parseRpsProd195a("RPS:scissors|x"))
        assertEquals("rock", parseRpsProd195a("RPS:rock"))
        assertEquals(null, parseRpsProd195a("RPS:"))
        assertEquals(null, parseRpsProd195a("RPS:  "))
        assertEquals(null, parseRpsProd195a("rps:rock|x"))
        assertEquals(null, parseRpsProd195a(""))
        // formatRps 不转义标签，标签原样进消息；解析只取第一个 | 前的选项名。
        assertEquals("RPS:rock|a|b^c played [R] rock", formatRpsProd195a("rock", "a|b^c"))
        assertEquals("rock", parseRpsProd195a("RPS:rock|a\u0001b\u0002c played [R] rock"))
        // 数字炸弹：格式化不校验，解析只认前两段整数。
        assertEquals("BOMB:100:42|Bob started number bomb (1-100)", formatNumberBombProd195a(42, 100, "Bob"))
        assertEquals("BOMB:10:1| started number bomb (1-10)", formatNumberBombProd195a(1, 10, ""))
        assertEquals("BOMB:-100:-5|H started number bomb (1--100)", formatNumberBombProd195a(-5, -100, "H"))
        assertEquals(Triple(100, 42, "Bob started number bomb (1-100)"), parseNumberBombProd195a("BOMB:100:42|Bob started number bomb (1-100)"))
        assertEquals(Triple(100, 42, ""), parseNumberBombProd195a("BOMB:100:42"))
        assertEquals(null, parseNumberBombProd195a("BOMB:abc:5|x"))
        assertEquals(null, parseNumberBombProd195a("BOMB:5|x"))
        assertEquals(null, parseNumberBombProd195a("BOMB:"))
        assertEquals(Triple(100, 42, "x"), parseNumberBombProd195a("BOMB:100:42:7|x"))
        assertEquals(null, parseNumberBombProd195a("bomb:100:42|x"))
        // 表情赛跑：token 超 16 码元截断，消息里的 token 是原文。
        assertEquals("RACE:\ud83d\ude80|Host reaction race: first to reply with \ud83d\ude80 wins", formatReactionRaceProd195a("\ud83d\ude80", "Host"))
        assertEquals("RACE:aaaaaaaaaaaaaaaa|H reaction race: first to reply with aaaaaaaaaaaaaaaa wins", formatReactionRaceProd195a("a".repeat(17), "H"))
        assertEquals("RACE:a\u0001b|H reaction race: first to reply with a|b wins", formatReactionRaceProd195a("a|b", "H"))
        assertEquals("\ud83d\ude80" to "Host reaction race: first to reply with \ud83d\ude80 wins", parseReactionRaceProd195a("RACE:\ud83d\ude80|Host reaction race: first to reply with \ud83d\ude80 wins"))
        assertEquals(null, parseReactionRaceProd195a("RACE:"))
        assertEquals(null, parseReactionRaceProd195a("RACE:|x"))
        assertEquals("\ud83d\ude80" to "", parseReactionRaceProd195a("RACE:\ud83d\ude80"))
        assertEquals(null, parseReactionRaceProd195a("race:\ud83d\ude80|x"))
        // 每条固定向量 oracle 与生产一致。
        for (c in listOf(
            PkCase195a("rps", "rock", "Alice"),
            PkCase195a("prps", "RPS:rock|Alice played [R] rock"),
            PkCase195a("bomb", "Bob", "", 42, 100),
            PkCase195a("pbomb", "BOMB:100:42|Bob started number bomb (1-100)"),
            PkCase195a("race", "\ud83d\ude80", "Host"),
            PkCase195a("prace", "RACE:\ud83d\ude80|Host reaction race: first to reply with \ud83d\ude80 wins"),
        )) assertEquals(pkOracle195a(c), pkProd195a(c))
    }
}
