// AgentChatUpdatePolicy fuzz (146th block). Oracle reimplements
// updateEntries/formatUpdated from production text: updateEntries
// walks the four fixed keys in order and keeps the ones parseBool
// accepts (trim + lowercase, then the true/false word table);
// formatUpdated interpolates "Updated <chatId> k=v ...".
// parseBool itself is already fuzz-covered (codec-fuzz-nov22); this
// block covers the policy functions that call it, so the oracle
// reimplements parseBool here instead of calling AgentToolArgs.
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// Java Character.isWhitespace set (28 units); Kotlin trim()/isBlank()
// use exactly this, not the <= 0x20 rule.
private val WS146 = setOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', '\u0020', '\u00A0', '\u1680', '\u2000', '\u2001',
    '\u2002', '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008',
    '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
)

private fun trim146(s: String): String {
    var i = 0
    var j = s.length
    while (i < j && s[i] in WS146) i++
    while (j > i && s[j - 1] in WS146) j--
    return s.substring(i, j)
}

private fun isBlank146(s: String): Boolean = s.all { it in WS146 }

private fun parseBoolOracle146(raw: String?): Boolean? = when (trim146(raw ?: "").lowercase()) {
    "true", "1", "yes" -> true
    "false", "0", "no" -> false
    else -> null
}

private val UPDATE_KEYS146 = listOf("pinned", "muted", "archived", "markedUnread")

private fun updateEntriesOracle146(args: Map<String, String>): List<Pair<String, Boolean>> =
    UPDATE_KEYS146.mapNotNull { key ->
        parseBoolOracle146(args[key])?.let { key to it }
    }

private fun formatUpdatedOracle146(chatId: String, entries: List<Pair<String, Boolean>>): String =
    "Updated $chatId ${entries.joinToString(" ") { "${it.first}=${it.second}" }}"

private fun updateEntriesProduction146(args: Map<String, String>): List<Pair<String, Boolean>> =
    AgentChatUpdatePolicy.updateEntries(args)

private fun formatUpdatedProduction146(chatId: String, entries: List<Pair<String, Boolean>>): String =
    AgentChatUpdatePolicy.formatUpdated(chatId, entries)

// ---------- generators (mirrored by Oracle146 GEN, seed 146002) ----------

private val ARG_KEY_POOL146 = arrayOf(
    "pinned", "muted", "archived", "markedUnread",
    "other", "Pinned", "pinned ", "", "chatId", "muted2", "PINNED",
)
private val VALUE_POOL146 = arrayOf(
    "true", "false", "1", "0", "yes", "no", "TRUE", "FALSE", "Yes", "No",
    "YeS", "nO", "  true  ", "\tYES\n", "maybe", "2", "", "  ", "truee",
    " true", "false ", "TRUEE", "\u00A0true\u00A0", "truſe", "TRUİ", "yess",
    "on", "off", "-1", "01", "00", "t", "f", "y", "n",
)
private val CHATID_POOL146 = arrayOf(
    "", "c1", "chat_1", "  ", "a b", "c".repeat(100), "é", "msg_x",
    "pinned=true", "a=b", "x y z", "·",
)
private val ENTRY_KEY_POOL146 = arrayOf(
    "pinned", "muted", "archived", "markedUnread", "other", "",
)

private fun randomAcuString146(rnd: Random): String {
    val sb = StringBuilder()
    val len = rnd.nextInt(0, 12)
    repeat(len) {
        when (rnd.nextInt(5)) {
            0 -> sb.append('a' + rnd.nextInt(26))
            1 -> sb.append('0' + rnd.nextInt(10))
            2 -> sb.appendCodePoint(rnd.nextInt(0x20, 0x2FFF))
            3 -> sb.append(VALUE_POOL146[rnd.nextInt(VALUE_POOL146.size)])
            else -> sb.append(WS146.elementAt(rnd.nextInt(WS146.size)))
        }
    }
    return sb.toString()
}

private fun randomArgs146(rnd: Random): Map<String, String> {
    val n = rnd.nextInt(0, 7)
    val m = linkedMapOf<String, String>()
    repeat(n) { m[ARG_KEY_POOL146[rnd.nextInt(ARG_KEY_POOL146.size)]] = randomAcuString146(rnd) }
    return m
}

private fun randomChatId146(rnd: Random): String =
    if (rnd.nextBoolean()) CHATID_POOL146[rnd.nextInt(CHATID_POOL146.size)] else randomAcuString146(rnd)

private fun randomEntries146(rnd: Random): List<Pair<String, Boolean>> {
    val n = rnd.nextInt(0, 7)
    return List(n) { ENTRY_KEY_POOL146[rnd.nextInt(ENTRY_KEY_POOL146.size)] to rnd.nextBoolean() }
}

class AgentChatUpdatePolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val updateCases: List<Pair<Map<String, String>, List<Pair<String, Boolean>>>> = listOf(
            mapOf("archived" to "false", "pinned" to "yes", "muted" to "maybe") to
                listOf("pinned" to true, "archived" to false),
            mapOf("markedUnread" to "0", "muted" to "1") to
                listOf("muted" to true, "markedUnread" to false),
            emptyMap<String, String>() to emptyList(),
            mapOf("pinned" to "maybe") to emptyList(),
            mapOf("pinned" to "TRUE", "muted" to "FALSE", "archived" to "YeS", "markedUnread" to "nO") to
                listOf("pinned" to true, "muted" to false, "archived" to true, "markedUnread" to false),
            mapOf("pinned" to "  YeS  ", "muted" to "\tYES\n") to
                listOf("pinned" to true, "muted" to true),
            mapOf("pinned" to "truſe") to emptyList(),
            mapOf("pinned" to "TRUİ") to emptyList(),
            mapOf("pinned" to "\u00A0true\u00A0") to listOf("pinned" to true),
            mapOf("pinned" to "2", "muted" to "-1", "archived" to "01") to emptyList(),
            mapOf("pinned" to "truee", "muted" to "yess") to emptyList(),
            mapOf("pinned" to "", "muted" to "  ") to emptyList(),
            mapOf("other" to "true", "Pinned" to "true", "pinned " to "true") to emptyList(),
            mapOf("pinned" to "1", "pinned" to "0") to listOf("pinned" to false),
        )
        for ((args, want) in updateCases) {
            assertEquals(want, updateEntriesOracle146(args))
            assertEquals(updateEntriesOracle146(args), updateEntriesProduction146(args))
        }
        val formatCases: List<Triple<String, List<Pair<String, Boolean>>, String>> = listOf(
            Triple("c1", listOf("pinned" to true, "muted" to false), "Updated c1 pinned=true muted=false"),
            Triple("c1", emptyList(), "Updated c1 "),
            Triple("", listOf("pinned" to true), "Updated  pinned=true"),
            Triple("a b", listOf("other" to true, "" to false), "Updated a b other=true =false"),
            Triple("c1", listOf("pinned" to true, "pinned" to false), "Updated c1 pinned=true pinned=false"),
            Triple("pinned=true", listOf("x" to true), "Updated pinned=true x=true"),
            Triple("c".repeat(100), listOf("muted" to true), "Updated ${"c".repeat(100)} muted=true"),
        )
        for ((chatId, entries, want) in formatCases) {
            assertEquals(want, formatUpdatedOracle146(chatId, entries))
            assertEquals(formatUpdatedOracle146(chatId, entries), formatUpdatedProduction146(chatId, entries))
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(146002)
        repeat(1500) {
            val args = randomArgs146(rnd)
            assertEquals(updateEntriesOracle146(args), updateEntriesProduction146(args))
            val chatId = randomChatId146(rnd)
            val entries = randomEntries146(rnd)
            assertEquals(formatUpdatedOracle146(chatId, entries), formatUpdatedProduction146(chatId, entries))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Single-unit arg values for every key across ASCII + whitespace + boundary units.
        val units = (0..0x7F).toList() + WS146.map { it.code } +
            listOf(0x80, 0xFF, 0x100, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF)
        for (key in ARG_KEY_POOL146) {
            for (u in units) {
                val args = mapOf(key to String(charArrayOf(u.toChar())))
                assertEquals(updateEntriesOracle146(args), updateEntriesProduction146(args))
            }
        }
        // Word pool wrapped in whitespace for the four fixed keys.
        for (key in UPDATE_KEYS146) {
            for (w in VALUE_POOL146) {
                for (pad in listOf("", " ", "  ", "\t", "\u00A0", "\n")) {
                    val args = mapOf(key to (pad + w + pad))
                    assertEquals(updateEntriesOracle146(args), updateEntriesProduction146(args))
                }
            }
        }
        // Chat id length sweep + entries length sweep for formatUpdated.
        for (len in 0..40) {
            val chatId = "c".repeat(len)
            val entries = listOf("pinned" to true)
            assertEquals(formatUpdatedOracle146(chatId, entries), formatUpdatedProduction146(chatId, entries))
        }
        for (n in 0..8) {
            val entries = List(n) { "pinned" to (it % 2 == 0) }
            assertEquals(formatUpdatedOracle146("c1", entries), formatUpdatedProduction146("c1", entries))
        }
        // Single-unit chat ids and entry keys.
        for (u in units) {
            val chatId = String(charArrayOf(u.toChar()))
            val entries = listOf("pinned" to true)
            assertEquals(formatUpdatedOracle146(chatId, entries), formatUpdatedProduction146(chatId, entries))
            val entries2 = listOf(String(charArrayOf(u.toChar())) to true)
            assertEquals(formatUpdatedOracle146("c1", entries2), formatUpdatedProduction146("c1", entries2))
        }
    }
}
