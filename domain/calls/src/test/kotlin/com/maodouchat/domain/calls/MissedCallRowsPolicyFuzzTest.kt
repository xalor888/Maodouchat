package com.maodouchat.domain.calls

import com.maodouchat.core.model.MissedCall
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 未接来电行合并 fuzz（清单 931 行第二十一块）。
 * 只加测试、零生产改动：`MissedCallRowsPolicy.mergeRows`。
 * 固定种子，同一份输入每次跑出同一批用例。
 */
class MissedCallRowsPolicyFuzzTest {

    private val callTypes = listOf("VIDEO", "video", "Video", "vIdEo", "AUDIO", "audio", "", "VOICE", "VIDEO1")

    private fun randomStoreEntry(r: Random, idPool: List<String>, atPool: List<Long>): CallLogEntry {
        val id = idPool[r.nextInt(idPool.size)]
        return CallLogEntry(
            id = id,
            peerId = "p-$id",
            peerName = "Peer $id",
            isVideo = r.nextBoolean(),
            direction = if (r.nextBoolean()) Direction.OUTGOING else Direction.INCOMING,
            state = if (r.nextBoolean()) State.MISSED else State.ANSWERED,
            startedAt = atPool[r.nextInt(atPool.size)],
            durationMs = r.nextLong(0L, 600_000L),
        )
    }

    private fun randomMissed(r: Random, idPool: List<String>, atPool: List<Long>): MissedCall {
        val id = idPool[r.nextInt(idPool.size)]
        return MissedCall(
            id = id,
            callerId = "c-$id",
            callerName = "Caller $id",
            callType = callTypes[r.nextInt(callTypes.size)],
            receivedAt = atPool[r.nextInt(atPool.size)],
        )
    }

    // 与生产侧逐字同构的 oracle：本地记录优先、按 id 首次出现去重、按时间倒序（稳定排序）。
    private fun expectedRows(storeLog: List<CallLogEntry>, missedCalls: List<MissedCall>): List<CallLogRow> {
        val seen = HashSet<String>()
        val rows = buildList {
            storeLog.forEach { e ->
                if (seen.add(e.id)) {
                    add(
                        CallLogRow(e.id, e.peerId, e.peerName, e.isVideo, e.direction, e.state, e.startedAt, e.durationMs)
                    )
                }
            }
            missedCalls.forEach { mc ->
                if (seen.add(mc.id)) {
                    add(
                        CallLogRow(
                            mc.id, mc.callerId, mc.callerName,
                            mc.callType.equals("VIDEO", ignoreCase = true),
                            Direction.INCOMING, State.MISSED, mc.receivedAt, 0L,
                        )
                    )
                }
            }
        }
        return rows.sortedByDescending { it.at }
    }

    @Test
    fun `mergeRows matches independent oracle on random inputs`() {
        val r = Random(0x1155_0621L)
        val ids = listOf("a", "b", "c", "d", "e")
        val ats = listOf(0L, 100L, 200L, 300L, 1000L)
        repeat(200) {
            val storeLog = List(r.nextInt(0, 8)) { randomStoreEntry(r, ids, ats) }
            val missed = List(r.nextInt(0, 8)) { randomMissed(r, ids, ats) }
            assertEquals(expectedRows(storeLog, missed), MissedCallRowsPolicy.mergeRows(storeLog, missed))
        }
    }

    @Test
    fun `store row wins over missed row with same id`() {
        val r = Random(0x22AA_0621L)
        val ats = listOf(0L, 100L, 500L)
        repeat(200) {
            val store = randomStoreEntry(r, listOf("x"), ats)
            val missed = MissedCall("x", "c-x", "Caller x", callTypes[r.nextInt(callTypes.size)], ats[r.nextInt(ats.size)])
            val rows = MissedCallRowsPolicy.mergeRows(listOf(store), listOf(missed))
            assertEquals(1, rows.size)
            assertEquals(store.peerId, rows[0].peerId)
            assertEquals(store.isVideo, rows[0].video)
            assertEquals(store.direction, rows[0].direction)
            assertEquals(store.state, rows[0].state)
            assertEquals(store.startedAt, rows[0].at)
            assertEquals(store.durationMs, rows[0].durationMs)
        }
    }

    @Test
    fun `video flag is case-insensitive VIDEO match`() {
        val cases = mapOf(
            "VIDEO" to true, "video" to true, "Video" to true, "vIdEo" to true,
            " VIDEO " to false, "AUDIO" to false, "audio" to false, "" to false,
            "VIDEO1" to false, "VIDEOO" to false, "VID" to false,
        )
        for ((callType, expected) in cases) {
            val rows = MissedCallRowsPolicy.mergeRows(
                emptyList(),
                listOf(MissedCall("m", "c", "Caller", callType, 100L)),
            )
            assertEquals(expected, rows[0].video, "callType=$callType")
        }
    }

    @Test
    fun `duplicate ids keep first occurrence in each source`() {
        val r = Random(0x33DD_0621L)
        repeat(200) {
            val firstAt = r.nextLong(0L, 1000L)
            val secondAt = r.nextLong(0L, 1000L)
            val storeRows = MissedCallRowsPolicy.mergeRows(
                listOf(randomStoreEntry(r, listOf("d"), listOf(firstAt))),
                emptyList(),
            )
            assertEquals(1, storeRows.size)
            assertEquals(firstAt, storeRows[0].at)
            val missedRows = MissedCallRowsPolicy.mergeRows(
                emptyList(),
                listOf(
                    MissedCall("d", "c", "Caller", "AUDIO", firstAt),
                    MissedCall("d", "c2", "Caller2", "VIDEO", secondAt),
                ),
            )
            assertEquals(1, missedRows.size)
            assertEquals(firstAt, missedRows[0].at)
            assertEquals("c", missedRows[0].peerId)
        }
    }

    @Test
    fun `rows sort by time descending and keep stable order on ties`() {
        val r = Random(0x44EE_0621L)
        repeat(200) {
            // at 只取 0..2，强制大量并列，验稳定排序：同时间先本地后未接、同源内保序。
            val ats = listOf(0L, 1L, 2L)
            val storeLog = List(r.nextInt(0, 5)) { i ->
                randomStoreEntry(r, listOf("s$i"), ats)
            }
            val missed = List(r.nextInt(0, 5)) { i ->
                MissedCall("m$i", "c$i", "Caller $i", "AUDIO", ats[r.nextInt(ats.size)])
            }
            val rows = MissedCallRowsPolicy.mergeRows(storeLog, missed)
            assertEquals(rows.sortedByDescending { it.at }, rows)
            val ids = rows.map { it.id }
            assertEquals(ids.toSet().size, ids.size)
            // 同 at 的组内：本地行相对顺序与输入一致，未接行相对顺序与输入一致。
            for (at in ats) {
                val storeInGroup = rows.filter { it.at == at && it.id.startsWith("s") }.map { it.id }
                val inputStore = storeLog.filter { it.startedAt == at }.map { it.id }
                assertEquals(inputStore, storeInGroup)
                val missedInGroup = rows.filter { it.at == at && it.id.startsWith("m") }.map { it.id }
                val inputMissed = missed.filter { it.receivedAt == at }.map { it.id }
                assertEquals(inputMissed, missedInGroup)
            }
        }
        // 并列钉住：同 at 时本地行在未接行之前。
        val rows = MissedCallRowsPolicy.mergeRows(
            listOf(CallLogEntry("s", "p", "n", false, Direction.INCOMING, State.ANSWERED, 100L, 0L)),
            listOf(MissedCall("m", "c", "Caller", "AUDIO", 100L)),
        )
        assertEquals(listOf("s", "m"), rows.map { it.id })
    }
}
