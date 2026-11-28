package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// String.compareTo reimplemented by hand over UTF-16 units: first
// differing unit decides, otherwise the shorter string is smaller.
internal fun compareIdOracle166(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val d = a[i].code - b[i].code
        if (d != 0) return d
    }
    return a.length - b.length
}

// plan reimplemented from the production branches: the four gates in
// order (no chat / not active / owner blank or the "me" placeholder /
// session gate closed) all skip the round without touching seen;
// unread is other-sender, non-SK_DIST, non-READ and not seen; the
// watermark is the max by (timestamp, id) with first-maximal kept on
// a full tie; unread ids keep first-occurrence order and are added
// to seen only when a plan is actually formed. The result is the
// plan plus the seen list after the call, so the mutation is checked
// too.
internal fun planOracle166(
    messages: List<Message>,
    hasChat: Boolean,
    isActiveChat: Boolean,
    ownerUserId: String,
    sessionMayContinue: Boolean,
    seen: List<String>
): Pair<Plan?, List<String>> {
    val seenUnique = seen.distinct()
    if (!hasChat) return null to seenUnique
    if (!isActiveChat) return null to seenUnique
    if (ownerUserId.isBlank() || ownerUserId == "me") return null to seenUnique
    if (!sessionMayContinue) return null to seenUnique
    val seenSet = seenUnique.toHashSet()
    val unread = messages.filter { m ->
        m.senderId != ownerUserId && m.type != MessageType.SK_DIST &&
            m.status != MessageStatus.READ && m.id !in seenSet
    }
    if (unread.isEmpty()) return null to seenUnique
    var best = unread[0]
    for (m in unread) {
        if (m.timestamp > best.timestamp) best = m
        else if (m.timestamp == best.timestamp && compareIdOracle166(m.id, best.id) > 0) best = m
    }
    val unreadIds = LinkedHashSet<String>()
    for (m in unread) unreadIds.add(m.id)
    val newSeen = ArrayList(seenUnique)
    for (id in unreadIds) if (id !in newSeen) newSeen.add(id)
    return Plan(unreadIds, best.id, best.timestamp) to newSeen
}

// rollbackAfterFailure reimplemented: drop exactly the plan's ids
// from seen, keeping the remaining order.
internal fun rollbackOracle166(seen: List<String>, plan: Plan): List<String> {
    val out = ArrayList(seen)
    out.removeAll(plan.unreadIds)
    return out
}

// ---------- generators ----------

internal fun watMessage166(
    id: String,
    sender: String,
    type: MessageType,
    status: MessageStatus,
    ts: Long
): Message =
    Message(id = id, chatId = "c166", senderId = sender, content = "", type = type, timestamp = ts, status = status)

internal data class WatCase166(
    val messages: List<Message>,
    val hasChat: Boolean,
    val isActiveChat: Boolean,
    val owner: String,
    val mayContinue: Boolean,
    val seen: List<String>,
    val rollback: Boolean
)

internal val WAT_ID_POOL166: List<String> = listOf(
    "m1", "m2", "m3", "", "a", "A", "m1 ", " m1",
    "\u00A0", "\uD83D\uDE00", "\uD800", "x", "z",
)

internal val WAT_SENDER_POOL166: List<String> = listOf(
    "u1", "u2", "me", "", " u1", "u1 ", "u3",
)

internal val WAT_OWNER_POOL166: List<String> = listOf(
    "u1", "u2", "me", "", " ", "\u00A0", " u1", "u1 ",
)

internal val WAT_TS_POOL166: List<Long> = listOf(
    0L, 1L, -1L, 2L, 100L, 99L, 101L, Long.MAX_VALUE, Long.MIN_VALUE,
)

internal fun randomWatCase166(rnd: Random): WatCase166 {
    val n = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(1, 3)
        else -> rnd.nextInt(0, 6)
    }
    val messages = List(n) {
        watMessage166(
            WAT_ID_POOL166[rnd.nextInt(WAT_ID_POOL166.size)],
            WAT_SENDER_POOL166[rnd.nextInt(WAT_SENDER_POOL166.size)],
            if (rnd.nextInt(100) < 15) MessageType.SK_DIST
            else MessageType.entries[rnd.nextInt(MessageType.entries.size)],
            MessageStatus.entries[rnd.nextInt(MessageStatus.entries.size)],
            WAT_TS_POOL166[rnd.nextInt(WAT_TS_POOL166.size)],
        )
    }
    val seenN = rnd.nextInt(0, 5)
    val seen = List(seenN) { WAT_ID_POOL166[rnd.nextInt(WAT_ID_POOL166.size)] }
    return WatCase166(
        messages,
        rnd.nextInt(10) != 0,
        rnd.nextInt(10) != 0,
        WAT_OWNER_POOL166[rnd.nextInt(WAT_OWNER_POOL166.size)],
        rnd.nextInt(10) != 0,
        seen,
        rnd.nextInt(10) < 3,
    )
}

internal fun watWireJson166(c: WatCase166): String {
    fun esc(s: String): String {
        val sb = StringBuilder()
        sb.append("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
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
    fun strList(items: List<String>): String {
        val sb = StringBuilder()
        sb.append("[")
        for ((i, s) in items.withIndex()) {
            if (i > 0) sb.append(",")
            sb.append(esc(s))
        }
        sb.append("]")
        return sb.toString()
    }
    val sb = StringBuilder()
    sb.append("{\"msgs\":[")
    for ((i, m) in c.messages.withIndex()) {
        if (i > 0) sb.append(",")
        sb.append("{\"id\":${esc(m.id)},\"sender\":${esc(m.senderId)},")
        sb.append("\"type\":${m.type.ordinal},\"status\":${m.status.ordinal},\"ts\":${m.timestamp}}")
    }
    sb.append("],\"hasChat\":${c.hasChat},\"active\":${c.isActiveChat},")
    sb.append("\"owner\":${esc(c.owner)},\"mayContinue\":${c.mayContinue},")
    sb.append("\"seen\":${strList(c.seen)},\"rollback\":${c.rollback}}")
    return sb.toString()
}

class ChatReadWatermarkPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val t = MessageType.TEXT
        val sent = MessageStatus.SENT
        val read = MessageStatus.READ
        val sk = MessageType.SK_DIST
        fun check(
            messages: List<Message>,
            hasChat: Boolean,
            active: Boolean,
            owner: String,
            mayContinue: Boolean,
            seen: List<String>,
            expected: Plan?,
            expectedSeen: List<String>,
            rollback: Boolean = false,
            expectedSeenAfterRollback: List<String>? = null
        ) {
            val seenSet = SeenSet()
            seenSet.addAll(seen)
            val input = ChatReadWatermarkInput(messages, hasChat, active, owner, mayContinue)
            val plan = ChatReadWatermarkPolicy.plan(input, seenSet)
            assertEquals(expected, plan, "plan($messages,$owner)")
            assertEquals(expectedSeen, seenSet.snapshot().toList(), "seenAfter($messages,$owner)")
            val (oraclePlan, oracleSeen) = planOracle166(messages, hasChat, active, owner, mayContinue, seen)
            assertEquals(expected, oraclePlan, "oraclePlan($messages,$owner)")
            assertEquals(expectedSeen, oracleSeen, "oracleSeen($messages,$owner)")
            if (rollback && plan != null) {
                ChatReadWatermarkPolicy.rollbackAfterFailure(seenSet, plan)
                assertEquals(expectedSeenAfterRollback, seenSet.snapshot().toList(), "seenAfterRollback")
                assertEquals(
                    expectedSeenAfterRollback,
                    rollbackOracle166(oracleSeen, plan),
                    "oracleRollback"
                )
            }
        }
        val m1 = watMessage166("m1", "u2", t, sent, 100L)
        val m2 = watMessage166("m2", "u2", t, sent, 200L)
        check(listOf(m1), false, true, "u1", true, emptyList(), null, emptyList())
        check(listOf(m1), true, false, "u1", true, emptyList(), null, emptyList())
        check(listOf(m1), true, true, "u1", false, emptyList(), null, emptyList())
        check(listOf(m1), true, true, "", true, emptyList(), null, emptyList())
        check(listOf(m1), true, true, " ", true, emptyList(), null, emptyList())
        check(listOf(m1), true, true, "\u00A0", true, emptyList(), null, emptyList())
        check(listOf(m1), true, true, "me", true, emptyList(), null, emptyList())
        check(emptyList(), true, true, "u1", true, emptyList(), null, emptyList())
        check(listOf(watMessage166("m1", "u1", t, sent, 100L)), true, true, "u1", true, emptyList(), null, emptyList())
        check(listOf(watMessage166("m1", "u2", sk, sent, 100L)), true, true, "u1", true, emptyList(), null, emptyList())
        check(listOf(watMessage166("m1", "u2", t, read, 100L)), true, true, "u1", true, emptyList(), null, emptyList())
        check(listOf(m1), true, true, "u1", true, listOf("m1"), null, listOf("m1"))
        check(
            listOf(m1), true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("m1"), "m1", 100L), listOf("m1"),
            rollback = true, expectedSeenAfterRollback = emptyList()
        )
        check(
            listOf(m1, m2), true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("m1", "m2"), "m2", 200L), listOf("m1", "m2"),
            rollback = true, expectedSeenAfterRollback = emptyList()
        )
        val tieB = watMessage166("b", "u2", t, sent, 100L)
        val tieA = watMessage166("a", "u2", t, sent, 100L)
        check(
            listOf(tieB, tieA), true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("b", "a"), "b", 100L), listOf("b", "a")
        )
        check(
            listOf(tieA, tieB), true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("a", "b"), "b", 100L), listOf("a", "b")
        )
        val tsZ = watMessage166("z", "u2", t, sent, 5L)
        val tsA = watMessage166("a", "u2", t, sent, 9L)
        check(
            listOf(tsZ, tsA), true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("z", "a"), "a", 9L), listOf("z", "a")
        )
        val dup1 = watMessage166("m1", "u2", t, sent, 1L)
        val dup2 = watMessage166("m1", "u2", t, sent, 5L)
        check(
            listOf(dup1, dup2), true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("m1"), "m1", 5L), listOf("m1")
        )
        check(
            listOf(dup1, dup2, m2), true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("m1", "m2"), "m2", 200L), listOf("m1", "m2")
        )
        check(
            listOf(m1, m2), true, true, "u1", true, listOf("x"),
            Plan(linkedSetOf("m1", "m2"), "m2", 200L), listOf("x", "m1", "m2")
        )
        check(
            listOf(m1, m2), true, true, "u1", true, listOf("m1"),
            Plan(linkedSetOf("m2"), "m2", 200L), listOf("m1", "m2")
        )
        check(
            listOf(watMessage166("m1", " u1", t, sent, 100L)), true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("m1"), "m1", 100L), listOf("m1")
        )
        check(
            listOf(m1), true, true, "me ", true, emptyList(),
            Plan(linkedSetOf("m1"), "m1", 100L), listOf("m1")
        )
        check(
            listOf(m1), true, true, " u1", true, emptyList(),
            Plan(linkedSetOf("m1"), "m1", 100L), listOf("m1")
        )
        val astral = watMessage166("\uD83D\uDE00", "u2", t, sent, 100L)
        val zed = watMessage166("z", "u2", t, sent, 100L)
        check(
            listOf(zed, astral), true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("z", "\uD83D\uDE00"), "\uD83D\uDE00", 100L),
            listOf("z", "\uD83D\uDE00")
        )
        val lo = watMessage166("\uD800", "u2", t, sent, 100L)
        val hi = watMessage166("\uDFFF", "u2", t, sent, 100L)
        check(
            listOf(lo, hi), true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("\uD800", "\uDFFF"), "\uDFFF", 100L),
            listOf("\uD800", "\uDFFF")
        )
        val statuses = listOf(
            MessageStatus.SENDING, MessageStatus.SENT,
            MessageStatus.DELIVERED, MessageStatus.FAILED
        )
        val mixed = statuses.mapIndexed { i, s ->
            watMessage166("s$i", "u2", t, s, (i + 1).toLong())
        }
        check(
            mixed, true, true, "u1", true, emptyList(),
            Plan(linkedSetOf("s0", "s1", "s2", "s3"), "s3", 4L),
            listOf("s0", "s1", "s2", "s3")
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(166001)
        repeat(4000) {
            val c = randomWatCase166(rnd)
            val seenSet = SeenSet()
            seenSet.addAll(c.seen)
            val input = ChatReadWatermarkInput(
                c.messages, c.hasChat, c.isActiveChat, c.owner, c.mayContinue
            )
            val plan = ChatReadWatermarkPolicy.plan(input, seenSet)
            val (oraclePlan, oracleSeen) = planOracle166(
                c.messages, c.hasChat, c.isActiveChat, c.owner, c.mayContinue, c.seen
            )
            assertEquals(oraclePlan, plan, "plan($c)")
            assertEquals(oracleSeen, seenSet.snapshot().toList(), "seenAfter($c)")
            if (c.rollback && plan != null) {
                ChatReadWatermarkPolicy.rollbackAfterFailure(seenSet, plan)
                assertEquals(
                    rollbackOracle166(oracleSeen, plan),
                    seenSet.snapshot().toList(),
                    "seenAfterRollback($c)"
                )
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        val t = MessageType.TEXT
        val sent = MessageStatus.SENT
        val m1 = watMessage166("m1", "u2", t, sent, 10L)
        val m2own = watMessage166("m2", "u1", t, sent, 20L)
        val fixed = listOf(m1, m2own)
        for (owner in listOf("", " ", "\u00A0", "me", "u1", " u1", "u2")) {
            for (hasChat in listOf(false, true)) {
                for (active in listOf(false, true)) {
                    for (mayContinue in listOf(false, true)) {
                        val seenSet = SeenSet()
                        val input = ChatReadWatermarkInput(fixed, hasChat, active, owner, mayContinue)
                        val plan = ChatReadWatermarkPolicy.plan(input, seenSet)
                        val (oraclePlan, oracleSeen) = planOracle166(fixed, hasChat, active, owner, mayContinue, emptyList())
                        assertEquals(oraclePlan, plan, "plan(gates owner=$owner hc=$hasChat ac=$active mc=$mayContinue)")
                        assertEquals(oracleSeen, seenSet.snapshot().toList(), "seenAfter(gates)")
                    }
                }
            }
        }
        for (sender in listOf("u1", "u2", "me", "")) {
            for (type in MessageType.entries) {
                for (status in MessageStatus.entries) {
                    for (ts in listOf(0L, 1L)) {
                        val m = watMessage166("m1", sender, type, status, ts)
                        val seenSet = SeenSet()
                        val input = ChatReadWatermarkInput(listOf(m), true, true, "u1", true)
                        val plan = ChatReadWatermarkPolicy.plan(input, seenSet)
                        val (oraclePlan, oracleSeen) = planOracle166(listOf(m), true, true, "u1", true, emptyList())
                        assertEquals(oraclePlan, plan, "plan(single $sender,$type,$status,$ts)")
                        assertEquals(oracleSeen, seenSet.snapshot().toList(), "seenAfter(single)")
                    }
                }
            }
        }
        val ids = listOf("a", "b")
        val senders = listOf("u1", "u2")
        val tss = listOf(0L, 1L)
        val seenSubsets = listOf(
            emptyList(), listOf("a"), listOf("b"), listOf("a", "b")
        )
        for (id1 in ids) for (id2 in ids) {
            for (s1 in senders) for (s2 in senders) {
                for (ts1 in tss) for (ts2 in tss) {
                    for (seen in seenSubsets) {
                        val msgs = listOf(
                            watMessage166(id1, s1, t, sent, ts1),
                            watMessage166(id2, s2, t, sent, ts2)
                        )
                        val seenSet = SeenSet()
                        seenSet.addAll(seen)
                        val input = ChatReadWatermarkInput(msgs, true, true, "u1", true)
                        val plan = ChatReadWatermarkPolicy.plan(input, seenSet)
                        val (oraclePlan, oracleSeen) = planOracle166(msgs, true, true, "u1", true, seen)
                        assertEquals(oraclePlan, plan, "plan(pair $msgs seen=$seen)")
                        assertEquals(oracleSeen, seenSet.snapshot().toList(), "seenAfter(pair)")
                    }
                }
            }
        }
        val tieIds = listOf("a", "b", "A", "z", "\uD83D\uDE00", "\uD800", "\uDFFF", "")
        for (id1 in tieIds) {
            for (id2 in tieIds) {
                val msgs = listOf(
                    watMessage166(id1, "u2", t, sent, 100L),
                    watMessage166(id2, "u2", t, sent, 100L)
                )
                val seenSet = SeenSet()
                val input = ChatReadWatermarkInput(msgs, true, true, "u1", true)
                val plan = ChatReadWatermarkPolicy.plan(input, seenSet)
                val (oraclePlan, oracleSeen) = planOracle166(msgs, true, true, "u1", true, emptyList())
                assertEquals(oraclePlan, plan, "plan(tie $id1,$id2)")
                assertEquals(oracleSeen, seenSet.snapshot().toList(), "seenAfter(tie)")
            }
        }
        val seenPowers = listOf(
            emptyList(), listOf("m1"), listOf("m2"), listOf("x"),
            listOf("m1", "m2"), listOf("m1", "x"), listOf("m2", "x"),
            listOf("m1", "m2", "x")
        )
        val three = listOf(
            watMessage166("m1", "u2", t, sent, 1L),
            watMessage166("m2", "u2", t, sent, 2L),
            watMessage166("m3", "u1", t, sent, 3L)
        )
        for (seen in seenPowers) {
            val seenSet = SeenSet()
            seenSet.addAll(seen)
            val input = ChatReadWatermarkInput(three, true, true, "u1", true)
            val plan = ChatReadWatermarkPolicy.plan(input, seenSet)
            val (oraclePlan, oracleSeen) = planOracle166(three, true, true, "u1", true, seen)
            assertEquals(oraclePlan, plan, "plan(seenPowers $seen)")
            assertEquals(oracleSeen, seenSet.snapshot().toList(), "seenAfter(seenPowers)")
        }
    }
}
