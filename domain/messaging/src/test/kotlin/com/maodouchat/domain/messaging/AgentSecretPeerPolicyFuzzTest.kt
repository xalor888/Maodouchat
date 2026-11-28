package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Fuzz / random-oracle tests for AgentSecretPeerPolicy (checklist line
// 931, 64th block). Test-only, zero production changes. The oracle is
// hand-written: comma split that keeps empty pieces, trim that strips
// leading/trailing units in the probed 28-unit Kotlin whitespace set
// (TrimProbe.kt: trim()'s predicate is Char.isWhitespace(), so
// U+00A0/U+2007/U+202F ARE stripped while U+0000/U+0085 are NOT), and
// a blank check over the same 28-unit set (U+00A0/U+2007/U+202F count
// as blank, U+0085 NEL does not); it never calls split, trim or
// isBlank. isSecretChat is a hand-written unit-by-unit comparison
// with the literal "SECRET". Random participant lists mix comma-joined
// id tokens, whitespace padding of every kind, empty pieces,
// duplicates, case-near ids and lone surrogates.
class AgentSecretPeerPolicyFuzzTest {

// ---------- AgentSecretPeerPolicy oracle ----------

// The probed Kotlin blank set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as blank, U+0085 NEL does not. Hand-written, never calls
// Char.isWhitespace / String.isBlank.
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

// Hand-written unit-by-unit comparison with the literal "SECRET";
// never ==, so the oracle shares no call with production here.
private fun isSecretChatOracle(chatType: String): Boolean {
    val want = "SECRET"
    if (chatType.length != want.length) return false
    for (i in want.indices) {
        if (chatType[i] != want[i]) return false
    }
    return true
}

// Hand-written split on the literal comma: empty pieces are kept,
// including leading/trailing ones ("" splits to [""]). Never split().
private fun splitCommaOracle(s: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    for (c in s) {
        if (c == ',') {
            out.add(cur.toString())
            cur.clear()
        } else {
            cur.append(c)
        }
    }
    out.add(cur.toString())
    return out
}

// Hand-written trim: strip leading/trailing UTF-16 units in the
// probed 28-unit Kotlin whitespace set. TrimProbe.kt (stdlib 2.4.0)
// shows trim()'s predicate is Char.isWhitespace(): U+00A0/U+2007/
// U+202F ARE stripped, U+0000/U+0085 are NOT. Never trim().
private fun trimOracle(s: String): String {
    var lo = 0
    var hi = s.length
    while (lo < hi && s[lo].code in BLANK_UNITS_ORACLE) lo++
    while (hi > lo && s[hi - 1].code in BLANK_UNITS_ORACLE) hi--
    return s.substring(lo, hi)
}

private fun secretPeerIdsOracle(secretChatsParticipantIds: List<String>): Set<String> {
    val out = mutableSetOf<String>()
    for (raw in secretChatsParticipantIds) {
        for (piece in splitCommaOracle(raw)) {
            val trimmed = trimOracle(piece)
            if (!isBlankOracle(trimmed)) out.add(trimmed)
        }
    }
    return out
}

// ---------- shared generators (Random(42) in the CI tests) ----------

// The probed Kotlin whitespace set (28 UTF-16 code units): U+00A0/U+2007/
// U+202F count as whitespace, U+0085 NEL does not.
private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

// Code-point ranges the random strings draw from. Covers ASCII, Latin-1,
// Latin/Greek/Cyrillic/Armenian/Georgian scripts, Greek Extended,
// ligatures, fullwidth forms, astral letters (Deseret / Adlam /
// Medefaidrin / Vithkuqi), CJK, combining marks, digits of several
// scripts, and general punctuation.
private val CP_RANGES = arrayOf(
    0x20 to 0x7E, 0x09 to 0x0D, 0x1C to 0x1F, 0x0085 to 0x0085,
    0x00A0 to 0x00FF, 0x0100 to 0x024F, 0x0300 to 0x036F,
    0x0370 to 0x03FF, 0x0400 to 0x052F, 0x0530 to 0x058F,
    0x0661 to 0x0661, 0x06F0 to 0x06F0, 0x0966 to 0x0966,
    0x10D0 to 0x10FF, 0x1680 to 0x1680, 0x1E00 to 0x1EFF,
    0x1F00 to 0x1FFF, 0x2000 to 0x206F, 0x2C00 to 0x2CFF,
    0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xA640 to 0xA69F,
    0xA7C0 to 0xA7FF, 0xAB53 to 0xAB53, 0xAB70 to 0xAB70,
    0xFB00 to 0xFB17, 0xFEFF to 0xFEFF, 0xFF10 to 0xFF10,
    0xFF21 to 0xFF5A, 0x10400 to 0x1044F, 0x104A0 to 0x104A0,
    0x10570 to 0x105BC, 0x16E61 to 0x16E7F, 0x1E922 to 0x1E943,
    0x1F610 to 0x1F610
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = CP_RANGES[rnd.nextInt(CP_RANGES.size)]
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
            2 -> {
                // Lone surrogate (high or low): survives split/trim
                // untouched, encodes as 0x3F in UTF-8.
                sb.append(if (rnd.nextBoolean()) '\uD800' else '\uDC00'); n++
            }
            else -> {
                val cp = randomCodePoint(rnd)
                sb.append(Character.toChars(cp))
                n += if (cp >= 0x10000) 2 else 1
            }
        }
    }
    return sb.toString()
}

// ---------- block-64 generators ----------

// Id-like tokens: real-looking peer ids plus strings that stress the
// comma split (empty, comma-only, comma at the edges, duplicates,
// case-near ids, template shapes, lone surrogates, whitespace of
// every kind around the id).
private val ID_POOL = arrayOf(
    "", "a", "b", "c", "user1", "user2", "Alice", "alice", "ALICE",
    "SECRET", "peer-1", "peer_2", "0", "-1", "x", "y", "id",
    "\$x", "\${x}", "\ud800", "\udc00", "\ud83d\ude10",
    "\u00a0", "\u0085", "\u200b", "\ufeff", "\u2007", "\u202f", "\u3000",
    "  padded  ", "\u0009tab\u0009", "a b", "a,b", "a,,b"
)

private fun randomIdToken(rnd: Random): String =
    if (rnd.nextInt(4) == 0) randomString(rnd, 8)
    else ID_POOL[rnd.nextInt(ID_POOL.size)]

// One raw participant-ids string: 0-4 tokens joined with commas, each
// side padded with random whitespace (or none), so the split/trim/
// drop-blank/dedupe pipeline is exercised on both sides of every edge.
private fun randomParticipantString(rnd: Random): String {
    val n = when (rnd.nextInt(6)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4
        else -> rnd.nextInt(0, 5)
    }
    val sb = StringBuilder()
    repeat(n) { i ->
        if (i > 0) sb.append(",")
        // Random padding around each token: none, spaces, tabs, or a
        // Unicode blank (trim() strips U+00A0/U+2007 too, so padded
        // ids still land bare).
        when (rnd.nextInt(5)) {
            1 -> sb.append(" ")
            2 -> sb.append("\t")
            3 -> sb.append("\u00a0")
            4 -> sb.append("  ")
        }
        sb.append(randomIdToken(rnd))
        when (rnd.nextInt(5)) {
            1 -> sb.append(" ")
            2 -> sb.append("\n")
            3 -> sb.append("\u2007")
            4 -> sb.append(" ")
        }
    }
    // Sometimes a bare comma string or a random string instead.
    return when (rnd.nextInt(8)) {
        0 -> ",".repeat(rnd.nextInt(0, 4))
        1 -> randomString(rnd, 24)
        else -> sb.toString()
    }
}

private fun randomParticipantList(rnd: Random): List<String> {
    val n = when (rnd.nextInt(7)) {
        0 -> 0; 1 -> 1; 2 -> 2; 3 -> 3; 4 -> 4; 5 -> 5
        else -> rnd.nextInt(0, 7)
    }
    val out = mutableListOf<String>()
    repeat(n) { out.add(randomParticipantString(rnd)) }
    return out
}

// Chat types: near-"SECRET" strings (case, padding, prefix/suffix,
// truncation) plus random strings, so equality is exercised on both
// sides of every edge.
private val CHAT_TYPE_POOL = arrayOf(
    "SECRET", "secret", "Secret", "SECRET ", " SECRET", "SECRE", "SECRETT",
    "SEC", "SECRETS", "GROUP", "PRIVATE", "", " ", "S", "SECRE T",
    "SECRET\u00a0", "\ufeffSECRET", "SECRET\n", "\tSECRET",
    "\$x", "\${x}", "\ud800", "\ud83d\ude10"
)

private fun randomChatType(rnd: Random): String = when (rnd.nextInt(4)) {
    0 -> CHAT_TYPE_POOL[rnd.nextInt(CHAT_TYPE_POOL.size)]
    1 -> "SECRET" + randomString(rnd, 4)
    2 -> randomString(rnd, 4) + "SECRET"
    else -> randomString(rnd, 12)
}

    @Test
    fun secretPeerIdsRandom() {
        val rnd = Random(42)
        repeat(300) {
            val ids = randomParticipantList(rnd)
            assertEquals(secretPeerIdsOracle(ids), AgentSecretPeerPolicy.secretPeerIds(ids))
        }
    }

    @Test
    fun isSecretChatRandom() {
        val rnd = Random(42)
        repeat(300) {
            val chatType = randomChatType(rnd)
            assertEquals(isSecretChatOracle(chatType), AgentSecretPeerPolicy.isSecretChat(chatType))
        }
    }

    @Test
    fun pinnedEdgeCases() {
        // isSecretChat: only the exact literal "SECRET" matches;
        // case, padding, prefix/suffix and truncation all fail.
        assertEquals(true, AgentSecretPeerPolicy.isSecretChat("SECRET"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("secret"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("Secret"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("SECRET "))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat(" SECRET"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("SECRE"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("SECRETT"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("GROUP"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat(""))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat(" "))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("S"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("SECRE T"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("SECRET\u00a0"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("\ufeffSECRET"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("SECRET\u000a"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("\u0009SECRET"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("\$x"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("\ud800"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("\ud83d\ude10"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("sECRET"))
        assertEquals(false, AgentSecretPeerPolicy.isSecretChat("SECRETSECRET"))

        // secretPeerIds: comma split keeps empty pieces, trim
        // strips the 28-unit whitespace set (U+00A0/U+2007/U+202F
        // included, U+0000/U+0085 excluded), blanks are dropped,
        // ids dedupe into a set, case-near ids stay distinct.
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(emptyList<String>()))
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(listOf("")))
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(listOf(",")))
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(listOf(",,")))
        assertEquals(setOf("a"), AgentSecretPeerPolicy.secretPeerIds(listOf("a")))
        assertEquals(setOf("a"), AgentSecretPeerPolicy.secretPeerIds(listOf("a,")))
        assertEquals(setOf("a"), AgentSecretPeerPolicy.secretPeerIds(listOf(",a")))
        assertEquals(setOf("a", "b"), AgentSecretPeerPolicy.secretPeerIds(listOf("a,,b")))
        assertEquals(setOf("a", "b", "c"), AgentSecretPeerPolicy.secretPeerIds(listOf("a, b,, c ", "b", "")))
        assertEquals(setOf("Alice", "alice"), AgentSecretPeerPolicy.secretPeerIds(listOf("Alice,alice")))
        assertEquals(setOf("Alice", "alice"), AgentSecretPeerPolicy.secretPeerIds(listOf("Alice", "alice")))
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(listOf(" , ")))
        assertEquals(setOf("a"), AgentSecretPeerPolicy.secretPeerIds(listOf("a,a,a")))
        assertEquals(setOf("a"), AgentSecretPeerPolicy.secretPeerIds(listOf(" a , a ")))
        assertEquals(setOf("SECRET"), AgentSecretPeerPolicy.secretPeerIds(listOf("SECRET")))
        assertEquals(setOf("a", "b", "c", "d"), AgentSecretPeerPolicy.secretPeerIds(listOf("a,b", "c,d")))
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(listOf("\u00a0")))
        assertEquals(setOf("\u0085"), AgentSecretPeerPolicy.secretPeerIds(listOf("\u0085")))
        assertEquals(setOf("\u200b"), AgentSecretPeerPolicy.secretPeerIds(listOf("\u200b")))
        assertEquals(setOf("\ufeff"), AgentSecretPeerPolicy.secretPeerIds(listOf("\ufeff")))
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(listOf("\u2007")))
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(listOf("\u202f")))
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(listOf("\u3000")))
        assertEquals(setOf("a"), AgentSecretPeerPolicy.secretPeerIds(listOf("\u00a0a\u00a0")))
        assertEquals(setOf("\u0085a\u0085"), AgentSecretPeerPolicy.secretPeerIds(listOf("\u0085a\u0085")))
        assertEquals(setOf("x"), AgentSecretPeerPolicy.secretPeerIds(listOf("\u2007x\u2007")))
        assertEquals(setOf("a"), AgentSecretPeerPolicy.secretPeerIds(listOf("\u3000a\u3000")))
        assertEquals(setOf("\ud800"), AgentSecretPeerPolicy.secretPeerIds(listOf("\ud800")))
        assertEquals(setOf("\udc00"), AgentSecretPeerPolicy.secretPeerIds(listOf("\udc00")))
        assertEquals(setOf("\ud800", "\udc00"), AgentSecretPeerPolicy.secretPeerIds(listOf("\ud800,\udc00")))
        assertEquals(setOf("\ud83d\ude10"), AgentSecretPeerPolicy.secretPeerIds(listOf("\ud83d\ude10")))
        assertEquals(setOf("a\u000ab"), AgentSecretPeerPolicy.secretPeerIds(listOf("\u0009a\u000ab")))
        assertEquals(setOf("padded"), AgentSecretPeerPolicy.secretPeerIds(listOf("  padded  ")))
        assertEquals(setOf("a b", "c d"), AgentSecretPeerPolicy.secretPeerIds(listOf("a b", "c d")))
        assertEquals(setOf("x", "y", "z"), AgentSecretPeerPolicy.secretPeerIds(listOf("x,y,z", "x", "y")))
        assertEquals(setOf("peer-1", "peer_2"), AgentSecretPeerPolicy.secretPeerIds(listOf("peer-1,peer_2", "peer-1")))
        assertEquals(setOf("-1", "0"), AgentSecretPeerPolicy.secretPeerIds(listOf("0", "-1", "0")))
        assertEquals(setOf("\ufeffa\ufeff"), AgentSecretPeerPolicy.secretPeerIds(listOf("\ufeffa\ufeff")))
        assertEquals(setOf("a\ufeffb"), AgentSecretPeerPolicy.secretPeerIds(listOf("a\ufeffb")))
        assertEquals(setOf("\u200b\u200b"), AgentSecretPeerPolicy.secretPeerIds(listOf("\u200b\u200b")))
        assertEquals(setOf("a", "b", "c", "d", "e", "f", "g"), AgentSecretPeerPolicy.secretPeerIds(listOf("a,b,c,d,e,f,g")))
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(listOf("", "", "")))
        assertEquals(emptySet<String>(), AgentSecretPeerPolicy.secretPeerIds(listOf(" , , ")))
        assertEquals(setOf("ALICE", "Alice", "alice"), AgentSecretPeerPolicy.secretPeerIds(listOf("Alice", "Alice", "alice", "ALICE")))
        assertEquals(setOf("\u0000a\u0000"), AgentSecretPeerPolicy.secretPeerIds(listOf("\u0000a\u0000")))
        assertEquals(setOf("\u0000"), AgentSecretPeerPolicy.secretPeerIds(listOf("\u0000")))
        assertEquals(setOf("a\u0000b"), AgentSecretPeerPolicy.secretPeerIds(listOf("a\u0000b")))    }
}
