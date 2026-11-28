package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolledIncomingBatchPolicyFuzzTest {

    private val random = Random(2026112246L)

    private val nowMs = 1_700_000_000_000L

    private val typePool = listOf(
        "offer", "OFFER", "Offer", "offer ", "hang-up", "HANG-UP", "Hang-Up",
        "busy", "BUSY", "reject", "Reject", "answer", "candidate", "", " ",
    )
    private val callIdPool = listOf("", "c1", "c2", "c3")
    private val userPool = listOf("", "alice", "bob")
    private val groupPool = listOf("", "g1")

    // 与生产侧逐行同构的 oracle：终端判定 / 未接墓碑 / prefer 取消 / offer
    // 保鲜 / 首选与 busy 回复 / 已处理去重，逐项复现。
    private fun oracleIsTerminalType(type: String): Boolean {
        val t = type.lowercase()
        return t == "hang-up" || t == "busy" || t == "reject"
    }

    private fun oracleIsOfferFresh(timestampMillis: Long, nowMillis: Long, maxAgeMs: Long): Boolean {
        if (timestampMillis <= 0L) return true
        if (maxAgeMs <= 0L) return false
        val age = nowMillis - timestampMillis
        if (age < 0L) return true
        return age <= maxAgeMs
    }

    private fun oracleShouldKeepOffer(
        type: String,
        callId: String,
        terminatedCallIds: Set<String>,
        timestampMillis: Long,
        nowMillis: Long,
    ): Boolean {
        if (!type.equals("offer", ignoreCase = true)) return false
        if (callId.isNotBlank() && callId in terminatedCallIds) return false
        return oracleIsOfferFresh(timestampMillis, nowMillis, SignalingOfferFreshnessPolicy.DEFAULT_MAX_AGE_MS)
    }

    private fun oracleDecide(
        messages: List<SignalMessage>,
        preferCallId: String,
        existingPending: PendingIncomingCall?,
        nowMs: Long,
    ): PolledIncomingBatchDecision {
        val terminals = messages.filter { oracleIsTerminalType(it.type) }
        val terminatedCallIds = terminals.map { it.callId }.filter { it.isNotBlank() }.toSet()
        val terminalDecisions = terminals.mapNotNull { terminal ->
            if (terminal.callId.isBlank()) return@mapNotNull null
            val missed = if (
                existingPending != null &&
                existingPending.callId == terminal.callId &&
                terminal.type.equals("hang-up", ignoreCase = true)
            ) {
                MissedRecordParams(
                    signalingCallId = terminal.callId,
                    fromUserId = existingPending.contactId.ifBlank { terminal.fromUserId },
                    callerName = existingPending.contactName,
                    isVideo = existingPending.callType == CallType.VIDEO,
                    isGroup = existingPending.groupId.isNotBlank(),
                )
            } else {
                null
            }
            TerminalDecision(callId = terminal.callId, missedRecord = missed)
        }
        val preferKilled = preferCallId.isNotBlank() && preferCallId in terminatedCallIds
        val offers = messages.filter {
            (it.groupId.isBlank() || it.groupInvite) &&
                oracleShouldKeepOffer(it.type, it.callId, terminatedCallIds, it.timestamp, nowMs)
        }
        val ghostPrefer = preferCallId.isNotBlank() && offers.none { it.callId == preferCallId }
        if (offers.isEmpty()) {
            return PolledIncomingBatchDecision(
                terminals = terminalDecisions,
                cancelPreferCallId = preferKilled || ghostPrefer,
                primary = null,
                shouldNavigate = false,
                busyReplies = emptyList(),
            )
        }
        val primaryIndex = offers.indexOfFirst { preferCallId.isNotBlank() && it.callId == preferCallId }
            .let { if (it >= 0) it else 0 }
        val primary = offers[primaryIndex]
        val rest = offers.filterIndexed { index, _ -> index != primaryIndex }
        val alreadyHandled = existingPending != null && (
            (primary.callId.isNotBlank() && existingPending.callId == primary.callId) ||
                (primary.callId.isBlank() && existingPending.contactId == primary.fromUserId)
            )
        return PolledIncomingBatchDecision(
            terminals = terminalDecisions,
            cancelPreferCallId = preferKilled || ghostPrefer,
            primary = primary,
            shouldNavigate = !alreadyHandled,
            busyReplies = rest,
        )
    }

    private fun randomTimestamp(): Long =
        when (random.nextInt(9)) {
            0 -> 0L
            1 -> -1L
            2 -> nowMs
            3 -> nowMs - 1L
            4 -> nowMs + 1L
            5 -> nowMs - 120_000L
            6 -> nowMs - 120_001L
            7 -> nowMs - 200_000L
            else -> nowMs + random.nextLong(-1_000_000L, 1_000_000L)
        }

    private fun randomMessage(index: Int): SignalMessage = SignalMessage(
        id = "m$index",
        fromUserId = userPool.random(random),
        type = typePool.random(random),
        payload = "p$index",
        timestamp = randomTimestamp(),
        callId = callIdPool.random(random),
        groupId = groupPool.random(random),
        groupInvite = random.nextBoolean(),
    )

    private fun randomPending(): PendingIncomingCall? =
        if (random.nextBoolean()) {
            null
        } else {
            PendingIncomingCall(
                contactId = userPool.random(random),
                contactName = "name",
                callType = listOf(CallType.AUDIO, CallType.VIDEO, CallType.GROUP).random(random),
                offerSdp = "",
                callId = callIdPool.random(random),
                groupId = groupPool.random(random),
            )
        }

    @Test
    fun `decide matches the model for random batches`() {
        repeat(200) {
            val messages = List(random.nextInt(0, 7)) { randomMessage(it) }
            val prefer = listOf("", "c1", "c2", "cX").random(random)
            val pending = randomPending()
            assertEquals(
                oracleDecide(messages, prefer, pending, nowMs),
                PolledIncomingBatchPolicy.decide(messages, prefer, pending, nowMs),
                "messages=$messages prefer=$prefer pending=$pending",
            )
        }
    }

    @Test
    fun `hang-up against pending records a missed call, busy and reject do not`() {
        val pending = PendingIncomingCall(
            contactId = "alice",
            contactName = "Alice",
            callType = CallType.VIDEO,
            offerSdp = "",
            callId = "c1",
        )
        val hangUp = SignalMessage("m1", "bob", "hang-up", "", nowMs, "c1")
        val decision = PolledIncomingBatchPolicy.decide(listOf(hangUp), "", pending, nowMs)
        assertEquals(
            listOf(
                TerminalDecision(
                    "c1",
                    MissedRecordParams("c1", "alice", "Alice", isVideo = true, isGroup = false),
                ),
            ),
            decision.terminals,
        )
        assertNull(decision.primary)
        for (type in listOf("busy", "reject", "BUSY", "REJECT")) {
            val terminal = SignalMessage("m1", "bob", type, "", nowMs, "c1")
            val other = PolledIncomingBatchPolicy.decide(listOf(terminal), "", pending, nowMs)
            assertEquals(listOf(TerminalDecision("c1", null)), other.terminals, "type=$type")
        }
        // 终端 callId 与 pending 不一致：不记未接。
        val foreign = SignalMessage("m1", "bob", "hang-up", "", nowMs, "c2")
        assertEquals(
            listOf(TerminalDecision("c2", null)),
            PolledIncomingBatchPolicy.decide(listOf(foreign), "", pending, nowMs).terminals,
        )
        // 无 pending：hang-up 也不记未接。
        assertEquals(
            listOf(TerminalDecision("c1", null)),
            PolledIncomingBatchPolicy.decide(listOf(hangUp), "", null, nowMs).terminals,
        )
    }

    @Test
    fun `missed record falls back to terminal sender and group flag`() {
        val pending = PendingIncomingCall(
            contactId = "",
            contactName = "Bob",
            callType = CallType.AUDIO,
            offerSdp = "",
            callId = "c1",
            groupId = "g1",
        )
        val hangUp = SignalMessage("m1", "bob", "HANG-UP", "", nowMs, "c1")
        val decision = PolledIncomingBatchPolicy.decide(listOf(hangUp), "", pending, nowMs)
        assertEquals(
            MissedRecordParams("c1", "bob", "Bob", isVideo = false, isGroup = true),
            decision.terminals.single().missedRecord,
        )
    }

    @Test
    fun `blank terminal callIds are dropped from decisions`() {
        val blank = SignalMessage("m1", "bob", "hang-up", "", nowMs, "")
        val blankBusy = SignalMessage("m2", "bob", "busy", "", nowMs, "   ")
        val decision = PolledIncomingBatchPolicy.decide(listOf(blank, blankBusy), "", null, nowMs)
        assertEquals(emptyList(), decision.terminals)
        assertNull(decision.primary)
        assertFalse(decision.shouldNavigate)
    }

    @Test
    fun `prefer is cancelled when killed or ghost`() {
        val offer = SignalMessage("m1", "alice", "offer", "", nowMs, "c1")
        // prefer 命中现存 offer：不取消。
        val alive = PolledIncomingBatchPolicy.decide(listOf(offer), "c1", null, nowMs)
        assertFalse(alive.cancelPreferCallId)
        assertEquals(offer, alive.primary)
        assertTrue(alive.shouldNavigate)
        // prefer 被终端覆盖：取消。
        val killed = SignalMessage("m2", "alice", "hang-up", "", nowMs, "c1")
        val dead = PolledIncomingBatchPolicy.decide(listOf(offer, killed), "c1", null, nowMs)
        assertTrue(dead.cancelPreferCallId)
        assertNull(dead.primary)
        // prefer 无对应 offer（幽灵响铃）：取消。
        val ghost = PolledIncomingBatchPolicy.decide(listOf(offer), "cX", null, nowMs)
        assertTrue(ghost.cancelPreferCallId)
        assertEquals(offer, ghost.primary)
        // prefer 为空：不取消。
        val empty = PolledIncomingBatchPolicy.decide(listOf(offer), "", null, nowMs)
        assertFalse(empty.cancelPreferCallId)
    }

    @Test
    fun `already handled pending suppresses navigation and rest become busy replies`() {
        val first = SignalMessage("m1", "alice", "offer", "", nowMs, "c1")
        val second = SignalMessage("m2", "bob", "offer", "", nowMs, "c2")
        val pending = PendingIncomingCall(
            contactId = "alice",
            contactName = "Alice",
            callType = CallType.AUDIO,
            offerSdp = "",
            callId = "c1",
        )
        val handled = PolledIncomingBatchPolicy.decide(listOf(first, second), "c1", pending, nowMs)
        assertEquals(first, handled.primary)
        assertFalse(handled.shouldNavigate)
        assertEquals(listOf(second), handled.busyReplies)
        // pending 是另一通：正常导航。
        val other = pending.copy(callId = "c9")
        val fresh = PolledIncomingBatchPolicy.decide(listOf(first, second), "c1", other, nowMs)
        assertTrue(fresh.shouldNavigate)
        assertEquals(listOf(second), fresh.busyReplies)
        // 首选 offer 的 callId 为空：按联系人去重。
        val blankCall = SignalMessage("m3", "alice", "offer", "", nowMs, "")
        val blankHandled = PolledIncomingBatchPolicy.decide(listOf(blankCall), "", pending, nowMs)
        assertEquals(blankCall, blankHandled.primary)
        assertFalse(blankHandled.shouldNavigate)
    }

    @Test
    fun `offer freshness pins the window edges`() {
        assertEquals(120_000L, SignalingOfferFreshnessPolicy.DEFAULT_MAX_AGE_MS)
        fun offerAt(timestamp: Long) =
            SignalMessage("m1", "alice", "offer", "", timestamp, "c1")
        assertEquals(offerAt(0L), PolledIncomingBatchPolicy.decide(listOf(offerAt(0L)), "", null, nowMs).primary)
        assertEquals(
            offerAt(nowMs + 1_000L),
            PolledIncomingBatchPolicy.decide(listOf(offerAt(nowMs + 1_000L)), "", null, nowMs).primary,
        )
        assertEquals(
            offerAt(nowMs - 120_000L),
            PolledIncomingBatchPolicy.decide(listOf(offerAt(nowMs - 120_000L)), "", null, nowMs).primary,
        )
        assertNull(PolledIncomingBatchPolicy.decide(listOf(offerAt(nowMs - 120_001L)), "", null, nowMs).primary)
        // 非 offer 类型不响铃；已终止的 callId 不响铃；空白 callId 的终端不挡 offer。
        val stale = SignalMessage("m1", "alice", "answer", "", nowMs, "c1")
        assertNull(PolledIncomingBatchPolicy.decide(listOf(stale), "", null, nowMs).primary)
        val terminated = SignalMessage("m2", "alice", "hang-up", "", nowMs, "c1")
        assertNull(
            PolledIncomingBatchPolicy.decide(listOf(offerAt(nowMs), terminated), "", null, nowMs).primary,
        )
        val blankTerminal = SignalMessage("m2", "alice", "hang-up", "", nowMs, "")
        assertEquals(
            offerAt(nowMs),
            PolledIncomingBatchPolicy.decide(listOf(offerAt(nowMs), blankTerminal), "", null, nowMs).primary,
        )
        // 群 mesh 边 offer（带 groupId 且非 invite）不走来电路由。
        val mesh = SignalMessage("m1", "alice", "offer", "", nowMs, "c1", groupId = "g1", groupInvite = false)
        assertNull(PolledIncomingBatchPolicy.decide(listOf(mesh), "", null, nowMs).primary)
        val invited = mesh.copy(groupInvite = true)
        assertEquals(invited, PolledIncomingBatchPolicy.decide(listOf(invited), "", null, nowMs).primary)
    }
}
