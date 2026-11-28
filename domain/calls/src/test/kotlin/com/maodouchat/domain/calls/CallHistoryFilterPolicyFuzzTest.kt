package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 通话记录过滤 fuzz（清单 931 行第二十二块）。
 * 只加测试、零生产改动：`CallHistoryFilterPolicy.kt` 的 `matches`/`filter`。
 * 固定种子，同一份输入每次跑出同一批用例。
 */
class CallHistoryFilterPolicyFuzzTest {

    private val textAlphabet = "abcXYZ019 _-.\"\\\n\t中"

    private val nameTokens = listOf(
        "Alice", "ALICE", "alice", "aLiCe", "Bob", "BOB", "bob",
        "zhang", "ZHANG", "Alice-Zhang", "u1", "U1", "alice1", "1alice",
    )

    private fun randomText(r: Random, length: Int): String =
        buildString { repeat(length) { append(textAlphabet[r.nextInt(textAlphabet.length)]) } }

    private fun randomName(r: Random): String = when (r.nextInt(3)) {
        0 -> nameTokens[r.nextInt(nameTokens.size)]
        1 -> nameTokens[r.nextInt(nameTokens.size)] + randomText(r, r.nextInt(0, 4))
        else -> randomText(r, r.nextInt(0, 10))
    }

    private fun randomEntry(r: Random, idPool: List<String>): CallLogEntry = CallLogEntry(
        id = idPool[r.nextInt(idPool.size)],
        peerId = randomName(r),
        peerName = randomName(r),
        isVideo = r.nextBoolean(),
        direction = if (r.nextBoolean()) Direction.OUTGOING else Direction.INCOMING,
        state = if (r.nextBoolean()) State.MISSED else State.ANSWERED,
        startedAt = r.nextLong(0L, 1_000_000_000_000L),
    )

    private fun randomQuery(r: Random): String = when (r.nextInt(5)) {
        0 -> ""
        1 -> " ".repeat(r.nextInt(1, 4))
        2 -> nameTokens[r.nextInt(nameTokens.size)]
        3 -> " " + nameTokens[r.nextInt(nameTokens.size)] + " "
        else -> randomText(r, r.nextInt(0, 8))
    }

    // 与生产侧不同的实现：逐位 regionMatches 扫描，大小写规则走同一套字符级折叠。
    private fun oracleContains(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return true
        if (needle.length > haystack.length) return false
        for (start in 0..haystack.length - needle.length) {
            if (haystack.regionMatches(start, needle, 0, needle.length, ignoreCase = true)) return true
        }
        return false
    }

    private fun oracleMatches(entry: CallLogEntry, query: String): Boolean =
        oracleContains(entry.peerName, query) || oracleContains(entry.peerId, query)

    private fun oracleFilter(logs: List<CallLogEntry>, query: String, onlyMissed: Boolean): List<CallLogEntry> {
        val base = logs.filter { entry -> !onlyMissed || entry.state == State.MISSED }
        val trimmed = query.trim()
        if (trimmed.isBlank()) return base
        return base.filter { entry -> oracleMatches(entry, trimmed) }
    }

    @Test
    fun `filter matches oracle on random logs and queries`() {
        val r = Random(0xF117_0622L)
        val ids = listOf("c1", "c2", "c3", "x", "y")
        repeat(200) {
            val logs = List(r.nextInt(0, 8)) { randomEntry(r, ids) }
            val query = randomQuery(r)
            val onlyMissed = r.nextBoolean()
            assertEquals(
                oracleFilter(logs, query, onlyMissed),
                CallHistoryFilterPolicy.filter(logs, query, onlyMissed),
            )
        }
    }

    @Test
    fun `matches matches oracle on random entries and queries`() {
        val r = Random(0x6D47_0622L)
        val ids = listOf("c1", "c2")
        repeat(200) {
            val entry = randomEntry(r, ids)
            val query = randomQuery(r)
            assertEquals(oracleMatches(entry, query), CallHistoryFilterPolicy.matches(entry, query))
        }
    }

    @Test
    fun `only missed gate keeps order and drops answered`() {
        val r = Random(0x9A7E_0622L)
        val ids = listOf("c1", "c2", "c3")
        repeat(200) {
            val logs = List(r.nextInt(0, 10)) { randomEntry(r, ids) }
            val missed = CallHistoryFilterPolicy.filter(logs, "", true)
            assertEquals(logs.filter { it.state == State.MISSED }, missed)
            assertTrue(missed.all { it.state == State.MISSED })
            // 空白查询不改变顺序与内容：只筛 state。
            assertEquals(
                logs.filter { it.state == State.MISSED },
                CallHistoryFilterPolicy.filter(logs, "  \t ", true),
            )
        }
    }

    @Test
    fun `blank query returns base unchanged`() {
        val logs = listOf(
            CallLogEntry("c1", "u1", "Alice", false, Direction.INCOMING, State.ANSWERED, 0L),
            CallLogEntry("c2", "u2", "Bob", false, Direction.INCOMING, State.MISSED, 0L),
        )
        assertEquals(logs, CallHistoryFilterPolicy.filter(logs, "", false))
        assertEquals(logs, CallHistoryFilterPolicy.filter(logs, "   ", false))
        assertEquals(listOf(logs[1]), CallHistoryFilterPolicy.filter(logs, "\n\t ", true))
        // 全角空格与 em 空格不在 trim 集里，但 isBlank 认它们是空白：仍回 base。
        assertEquals(logs, CallHistoryFilterPolicy.filter(logs, "\u3000", false))
        assertEquals(logs, CallHistoryFilterPolicy.filter(logs, "\u2000", false))
    }

    @Test
    fun `matches is case-insensitive and untrimmed`() {
        val entry = CallLogEntry("c1", "u1", "Alice", false, Direction.INCOMING, State.ANSWERED, 0L)
        assertTrue(CallHistoryFilterPolicy.matches(entry, "ali"))
        assertTrue(CallHistoryFilterPolicy.matches(entry, "ALI"))
        assertTrue(CallHistoryFilterPolicy.matches(entry, "U1"))
        assertTrue(CallHistoryFilterPolicy.matches(entry, ""))
        assertFalse(CallHistoryFilterPolicy.matches(entry, "zzz"))
        assertFalse(CallHistoryFilterPolicy.matches(entry, "AliceAliceAlice"))
        // matches 本身不 trim：带空格的查询字面匹配不到。
        assertFalse(CallHistoryFilterPolicy.matches(entry, " ali "))
        assertFalse(CallHistoryFilterPolicy.matches(entry, " Alice"))
        // filter 会先 trim，所以同样查询能命中。
        assertEquals(listOf(entry), CallHistoryFilterPolicy.filter(listOf(entry), " ali ", false))
    }
}
