package com.maodouchat.domain.calls

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 通话记录编解码 fuzz（清单 931 行第二十一块）。
 * 只加测试、零生产改动：`CallLogPolicy.kt` 的 6 个顶层函数。
 * 固定种子，同一份输入每次跑出同一批用例。
 */
class CallLogPolicyFuzzTest {

    private val textAlphabet = "abcXYZ019 _-.\"\\\n\t中"

    private fun randomText(r: Random, length: Int): String =
        buildString { repeat(length) { append(textAlphabet[r.nextInt(textAlphabet.length)]) } }

    // peerId 允许带空白：解码侧会 trim，oracle 按 trim 后的值比对。
    private fun randomPeerId(r: Random): String {
        val core = randomText(r, r.nextInt(0, 8)).replace("\n", "").replace("\t", "")
        return when (r.nextInt(4)) {
            0 -> "  $core  "
            1 -> "\t$core"
            2 -> "$core "
            else -> core
        }
    }

    private fun randomEntry(r: Random, idPool: List<String>): CallLogEntry = CallLogEntry(
        id = idPool[r.nextInt(idPool.size)],
        peerId = randomPeerId(r),
        peerName = randomText(r, r.nextInt(0, 12)),
        isVideo = r.nextBoolean(),
        direction = if (r.nextBoolean()) Direction.OUTGOING else Direction.INCOMING,
        state = if (r.nextBoolean()) State.MISSED else State.ANSWERED,
        startedAt = r.nextLong(0L, 1_000_000_000_000L),
        durationMs = r.nextLong(0L, 3_600_000L),
        isGroup = r.nextBoolean(),
    )

    private fun canonical(entry: CallLogEntry): CallLogEntry =
        entry.copy(peerId = entry.peerId.trim())

    @Test
    fun `entry codec round-trips random entries`() {
        val r = Random(0xCA11_0621L)
        val ids = listOf("c1", "c2", "c3", "x", "y")
        repeat(200) {
            val original = randomEntry(r, ids)
            assertEquals(
                canonical(original),
                decodeCallLogEntry(encodeCallLogEntry(original)),
            )
        }
        // 极值钉住：Long 两端往返不丢精度。
        val extreme = CallLogEntry(
            "edge", "p", "n", true, Direction.OUTGOING, State.MISSED,
            Long.MAX_VALUE, Long.MIN_VALUE, true,
        )
        assertEquals(extreme, decodeCallLogEntry(encodeCallLogEntry(extreme)))
    }

    @Test
    fun `wire words map both enum values`() {
        val r = Random(0xD1A2_0621L)
        repeat(200) {
            val direction = if (r.nextBoolean()) Direction.OUTGOING else Direction.INCOMING
            val state = if (r.nextBoolean()) State.MISSED else State.ANSWERED
            val entry = CallLogEntry("c", "p", "n", false, direction, state, 7L, 8L, false)
            val json = encodeCallLogEntry(entry)
            assertEquals(if (direction == Direction.OUTGOING) "out" else "in", json.getString("dir"))
            assertEquals(if (state == State.MISSED) "missed" else "answered", json.getString("state"))
            assertEquals(entry, decodeCallLogEntry(json))
        }
        // 非 "out"/"missed" 的 wire 词一律落回默认值。
        val decoded = decodeCallLogEntry(JSONObject().put("dir", "OUT").put("state", "Missed"))
        assertEquals(Direction.INCOMING, decoded.direction)
        assertEquals(State.ANSWERED, decoded.state)
    }

    @Test
    fun `decode trims peer whitespace only`() {
        assertEquals("u1", decodeCallLogEntry(JSONObject().put("peer", "  u1  ")).peerId)
        assertEquals("u1", decodeCallLogEntry(JSONObject().put("peer", "\tu1\n")).peerId)
        assertEquals("", decodeCallLogEntry(JSONObject().put("peer", "   ")).peerId)
        // id 与 name 不 trim：只有 peer 走 trim。
        val decoded = decodeCallLogEntry(JSONObject().put("id", " x ").put("name", " Alice "))
        assertEquals(" x ", decoded.id)
        assertEquals(" Alice ", decoded.peerName)
    }

    private val knownKeys = listOf("id", "peer", "name", "video", "dir", "state", "at", "dur", "group")
    private val unknownNames = listOf("zzz", "extra", "v2", "meta_data", "Id", "PEER", "at2")

    private fun randomJsonValue(r: Random, depth: Int): Any = when (r.nextInt(if (depth > 0) 6 else 8)) {
        0 -> randomText(r, r.nextInt(0, 8))
        1 -> r.nextInt(-1000, 1000)
        2 -> r.nextDouble(-1000.0, 1000.0)
        3 -> r.nextBoolean()
        4 -> JSONObject.NULL
        5 -> JSONObject().put("n", randomJsonValue(r, depth + 1))
        6 -> JSONArray().put(randomJsonValue(r, depth + 1))
        else -> r.nextLong(-100000L, 100000L)
    }

    @Test
    fun `decode ignores unknown fields and defaults missing ones`() {
        val r = Random(0x10CA_0621L)
        repeat(200) {
            val obj = JSONObject()
            var id = ""
            var peer = ""
            var name = ""
            var video = false
            var direction = Direction.INCOMING
            var state = State.ANSWERED
            var at = 0L
            var dur = 0L
            var group = false
            for (key in knownKeys.shuffled(r)) {
                if (!r.nextBoolean()) continue
                when (key) {
                    "id" -> { id = randomText(r, r.nextInt(0, 8)); obj.put(key, id) }
                    "peer" -> { peer = randomPeerId(r); obj.put(key, peer) }
                    "name" -> { name = randomText(r, r.nextInt(0, 8)); obj.put(key, name) }
                    "video" -> { video = r.nextBoolean(); obj.put(key, video) }
                    "dir" -> {
                        val word = listOf("out", "in", "OUT", "", "x")[r.nextInt(5)]
                        direction = if (word == "out") Direction.OUTGOING else Direction.INCOMING
                        obj.put(key, word)
                    }
                    "state" -> {
                        val word = listOf("missed", "answered", "Missed", "", "x")[r.nextInt(5)]
                        state = if (word == "missed") State.MISSED else State.ANSWERED
                        obj.put(key, word)
                    }
                    "at" -> { at = r.nextLong(0L, 1_000_000_000_000L); obj.put(key, at) }
                    "dur" -> { dur = r.nextLong(0L, 3_600_000L); obj.put(key, dur) }
                    "group" -> { group = r.nextBoolean(); obj.put(key, group) }
                }
            }
            repeat(r.nextInt(0, 4)) {
                obj.put(unknownNames[r.nextInt(unknownNames.size)], randomJsonValue(r, 0))
            }
            val expected = CallLogEntry(id, peer.trim(), name, video, direction, state, at, dur, group)
            assertEquals(expected, decodeCallLogEntry(obj))
        }
    }

    @Test
    fun `decodeCallLog sorts and skips non-object elements`() {
        val r = Random(0x11CA_0621L)
        val ids = listOf("a", "b", "c", "d")
        repeat(200) {
            val arr = JSONArray()
            val kept = mutableListOf<CallLogEntry>()
            repeat(r.nextInt(0, 8)) {
                when (r.nextInt(5)) {
                    0 -> arr.put(JSONObject.NULL)
                    1 -> arr.put(randomText(r, r.nextInt(1, 6)))
                    2 -> arr.put(r.nextInt(-100, 100))
                    3 -> arr.put(r.nextBoolean())
                    else -> {
                        val e = randomEntry(r, ids)
                        kept.add(canonical(e))
                        arr.put(encodeCallLogEntry(e))
                    }
                }
            }
            assertEquals(kept.sortedByDescending { it.startedAt }, decodeCallLog(arr.toString()))
        }
    }

    @Test
    fun `malformed json decodes to null`() {
        for (bad in listOf("", " ", "{not json", "{\"a\":1}", "[1,2", "null", "123", "\"str\"", "{]", "[}")) {
            assertNull(decodeCallLog(bad), "应返回 null：$bad")
        }
        assertEquals(emptyList(), decodeCallLog("[]"))
        assertEquals(emptyList(), decodeCallLog("[1,2]"))
        assertEquals(emptyList(), decodeCallLog("[null,\"x\",true]"))
    }

    @Test
    fun `upsert keeps list sorted unique and capped`() {
        val r = Random(0x12CA_0621L)
        repeat(200) {
            val baseIds = (0 until r.nextInt(0, 10)).map { "b$it" }.shuffled(r)
            val base = baseIds.map { id ->
                CallLogEntry(id, "p-$id", "n", false, Direction.INCOMING, State.ANSWERED, r.nextLong(0L, 1000L), 0L)
            }
            val fresh = r.nextBoolean() || base.isEmpty()
            val entry = if (fresh) {
                CallLogEntry("new-${r.nextInt(1000)}", "p", "n", true, Direction.OUTGOING, State.MISSED, r.nextLong(0L, 1000L), 5L)
            } else {
                val target = base[r.nextInt(base.size)]
                target.copy(durationMs = r.nextLong(0L, 9999L), startedAt = r.nextLong(0L, 1000L))
            }
            val maxEntries = r.nextInt(1, 12)
            val next = upsertCallLog(base, entry, maxEntries)
            // 降序、去重、上限三条不变量。
            assertEquals(next.sortedByDescending { it.startedAt }, next)
            assertEquals(next.map { it.id }.toSet().size, next.size)
            assertTrue(next.size <= maxEntries)
            val distinct = (base.map { it.id } + entry.id).toSet().size
            assertEquals(minOf(maxEntries, distinct), next.size)
            // 其余条目原样保留；被顶掉的只能是更旧的。
            for (kept in next) {
                if (kept.id == entry.id) assertEquals(entry, kept)
                else assertEquals(base.first { it.id == kept.id }, kept)
            }
            if (next.none { it.id == entry.id }) {
                assertEquals(maxEntries, next.size)
                assertTrue(next.all { it.startedAt >= entry.startedAt })
            }
        }
        // maxEntries 为 0/1 的边界。
        val base = listOf(CallLogEntry("a", "p", "n", false, Direction.INCOMING, State.ANSWERED, 10L, 0L))
        val late = CallLogEntry("b", "p", "n", false, Direction.INCOMING, State.ANSWERED, 20L, 0L)
        assertEquals(emptyList(), upsertCallLog(base, late, maxEntries = 0))
        assertEquals(listOf(late), upsertCallLog(base, late, maxEntries = 1))
    }

    @Test
    fun `upsert replaces same id and keeps count`() {
        val r = Random(0x13CA_0621L)
        repeat(200) {
            val base = (0 until r.nextInt(1, 8)).map { i ->
                CallLogEntry("c$i", "p", "n", false, Direction.INCOMING, State.ANSWERED, r.nextLong(0L, 1000L), 0L)
            }
            val target = base[r.nextInt(base.size)]
            val replacement = target.copy(durationMs = r.nextLong(0L, 9999L), isVideo = r.nextBoolean())
            val next = upsertCallLog(base, replacement)
            assertEquals(base.size, next.size)
            assertEquals(next.sortedByDescending { it.startedAt }, next)
            assertEquals(replacement, next.first { it.id == target.id })
            for (kept in next) {
                if (kept.id != target.id) assertEquals(base.first { it.id == kept.id }, kept)
            }
        }
    }

    @Test
    fun `remove drops only the named id`() {
        val r = Random(0x14CA_0621L)
        repeat(200) {
            val base = (0 until r.nextInt(0, 8)).map { i ->
                CallLogEntry("c$i", "p", "n", false, Direction.INCOMING, State.ANSWERED, i.toLong(), 0L)
            }
            val id = if (r.nextBoolean() && base.isNotEmpty()) base[r.nextInt(base.size)].id else "missing-${r.nextInt(100)}"
            assertEquals(base.filter { it.id != id }, removeCallLogEntry(base, id))
        }
    }
}
