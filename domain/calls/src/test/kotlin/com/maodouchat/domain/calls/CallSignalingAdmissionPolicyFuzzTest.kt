package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallSignalingAdmissionPolicyFuzzTest {

    private val random = Random(0xCA11_2026L)

    private val userIds = listOf("", "a", "b", "  ", "alice", "bob")
    private val callIds = listOf("", "call-1", "call-2", " ", "call-9")
    private val groupIds = listOf("", "g1", "g2", " ")
    private val memberPool = listOf("a", "b", "c")
    private val signalTypes = listOf(
        "offer", "answer", "ice-candidate", "hang-up", "busy", "reject",
        "OFFER", "Offer", "oFfEr", " offer ", "OFFER ", " offer",
        "", " ", "offerx", "offers", "of fer", "answer ",
    )
    private val offerVariants = listOf("offer", "OFFER", "Offer", "oFfEr", " offer ", "OFFER ", " offer")
    private val nonOfferTypes = listOf(
        "answer", "ice-candidate", "hang-up", "busy", "reject",
        "", " ", "offerx", "offers", "of fer", "answer ",
    )
    private val cursorValues = listOf(0L, 1L, 2L, 3L, Long.MAX_VALUE, Long.MIN_VALUE)

    private data class Input(
        val isGroupCall: Boolean,
        val expectedContactId: String,
        val fromUserId: String,
        val activeCallId: String,
        val incomingCallId: String,
        val activeGroupId: String,
        val incomingGroupId: String,
        val activeMembers: List<String>,
        val incomingMembers: List<String>,
        val signalType: String,
        val incomingEpoch: Long,
        val incomingSequence: Long,
        val lastAcceptedCursor: Cursor?,
    )

    private fun randomMembers(): List<String> {
        val size = random.nextInt(0, 4)
        return List(size) { memberPool[random.nextInt(memberPool.size)] }.shuffled(random)
    }

    private fun randomCursorValue(): Long =
        if (random.nextInt(4) == 0) random.nextLong() else cursorValues[random.nextInt(cursorValues.size)]

    private fun randomCursor(): Cursor = Cursor(randomCursorValue(), randomCursorValue())

    private fun randomInput(): Input = Input(
        isGroupCall = random.nextBoolean(),
        expectedContactId = userIds[random.nextInt(userIds.size)],
        fromUserId = userIds[random.nextInt(userIds.size)],
        activeCallId = callIds[random.nextInt(callIds.size)],
        incomingCallId = callIds[random.nextInt(callIds.size)],
        activeGroupId = groupIds[random.nextInt(groupIds.size)],
        incomingGroupId = groupIds[random.nextInt(groupIds.size)],
        activeMembers = randomMembers(),
        incomingMembers = randomMembers(),
        signalType = signalTypes[random.nextInt(signalTypes.size)],
        incomingEpoch = randomCursorValue(),
        incomingSequence = randomCursorValue(),
        lastAcceptedCursor = if (random.nextInt(4) == 0) null else randomCursor(),
    )

    // 决策树的逐字复述：发送者 → 群元数据 → callId → 游标；只在
    // 元数据/callId 失败且类型归一化为 offer 时忙拒绝，其余失败静默丢弃。
    // lowercase() 与生产用同一调用，locale 行为一致，不另行"修正"。
    private fun oracleAdmit(input: Input): Decision {
        if (input.fromUserId.isBlank()) return Decision.DROP
        if (!input.isGroupCall && input.expectedContactId.isNotBlank() &&
            input.expectedContactId != input.fromUserId
        ) {
            return Decision.DROP
        }
        val normalized = input.signalType.trim().lowercase()
        val metadataOk = input.activeGroupId.isBlank() || input.incomingGroupId.isBlank() ||
            (input.activeGroupId == input.incomingGroupId &&
                input.activeMembers.toSet() == input.incomingMembers.toSet())
        if (!metadataOk) return if (normalized == "offer") Decision.BUSY_REJECT else Decision.DROP
        val callIdOk = input.activeCallId.isBlank() || input.activeCallId == input.incomingCallId
        if (!callIdOk) return if (normalized == "offer") Decision.BUSY_REJECT else Decision.DROP
        val incoming = Cursor(input.incomingEpoch, input.incomingSequence)
        if (incoming.epoch == 0L && incoming.sequence == 0L) return Decision.ACCEPT
        val last = input.lastAcceptedCursor ?: return Decision.ACCEPT
        val byEpoch = incoming.epoch.compareTo(last.epoch)
        val cmp = if (byEpoch != 0) byEpoch else incoming.sequence.compareTo(last.sequence)
        return if (cmp < 0) Decision.DROP else Decision.ACCEPT
    }

    private fun actual(input: Input): Decision =
        CallSignalingAdmissionPolicy.admit(
            isGroupCall = input.isGroupCall,
            expectedContactId = input.expectedContactId,
            fromUserId = input.fromUserId,
            activeCallId = input.activeCallId,
            incomingCallId = input.incomingCallId,
            activeGroupId = input.activeGroupId,
            incomingGroupId = input.incomingGroupId,
            activeMembers = input.activeMembers,
            incomingMembers = input.incomingMembers,
            signalType = input.signalType,
            incomingEpoch = input.incomingEpoch,
            incomingSequence = input.incomingSequence,
            lastAcceptedCursor = input.lastAcceptedCursor,
        )

    private fun assertOracle(input: Input) {
        assertEquals(oracleAdmit(input), actual(input), "input=$input")
    }

    @Test
    fun `admit matches verbatim oracle over random inputs`() {
        repeat(200) { assertOracle(randomInput()) }
    }

    @Test
    fun `admit matches oracle over random group calls`() {
        repeat(150) { assertOracle(randomInput().copy(isGroupCall = true)) }
    }

    @Test
    fun `admit matches oracle over random direct calls`() {
        repeat(150) { assertOracle(randomInput().copy(isGroupCall = false)) }
    }

    @Test
    fun `accept implies sender metadata callId and cursor gates all pass`() {
        repeat(200) {
            val input = randomInput()
            if (actual(input) != Decision.ACCEPT) return@repeat
            assertTrue(input.fromUserId.isNotBlank(), "input=$input")
            assertTrue(
                input.isGroupCall || input.expectedContactId.isBlank() ||
                    input.expectedContactId == input.fromUserId,
                "input=$input",
            )
            assertTrue(
                input.activeGroupId.isBlank() || input.incomingGroupId.isBlank() ||
                    (input.activeGroupId == input.incomingGroupId &&
                        input.activeMembers.toSet() == input.incomingMembers.toSet()),
                "input=$input",
            )
            assertTrue(
                input.activeCallId.isBlank() || input.activeCallId == input.incomingCallId,
                "input=$input",
            )
            val incoming = Cursor(input.incomingEpoch, input.incomingSequence)
            val last = input.lastAcceptedCursor
            assertTrue(
                (incoming.epoch == 0L && incoming.sequence == 0L) || last == null ||
                    CallSignalingOrderPolicy.compare(incoming, last) >= 0,
                "input=$input",
            )
        }
    }

    @Test
    fun `busy reject implies offer and a later gate failed`() {
        repeat(200) {
            val input = randomInput()
            if (actual(input) != Decision.BUSY_REJECT) return@repeat
            assertEquals("offer", input.signalType.trim().lowercase(), "input=$input")
            assertTrue(input.fromUserId.isNotBlank(), "input=$input")
            assertTrue(
                input.isGroupCall || input.expectedContactId.isBlank() ||
                    input.expectedContactId == input.fromUserId,
                "input=$input",
            )
            val metadataOk = input.activeGroupId.isBlank() || input.incomingGroupId.isBlank() ||
                (input.activeGroupId == input.incomingGroupId &&
                    input.activeMembers.toSet() == input.incomingMembers.toSet())
            val callIdOk = input.activeCallId.isBlank() || input.activeCallId == input.incomingCallId
            assertTrue(!metadataOk || !callIdOk, "input=$input")
        }
    }

    @Test
    fun `same input always yields the same decision`() {
        repeat(100) {
            val input = randomInput()
            assertEquals(actual(input), actual(input), "input=$input")
        }
    }

    @Test
    fun `blank sender is dropped for every signal type`() {
        for (type in signalTypes) {
            repeat(5) {
                val input = randomInput().copy(fromUserId = if (random.nextBoolean()) "" else "  ", signalType = type)
                assertEquals(Decision.DROP, actual(input), "input=$input")
            }
        }
    }

    @Test
    fun `group call accepts any non-blank sender when other gates pass`() {
        repeat(100) {
            val sender = listOf("a", "b", "alice", "stranger")[random.nextInt(4)]
            val input = randomInput().copy(
                isGroupCall = true,
                expectedContactId = "someone-else",
                fromUserId = sender,
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                lastAcceptedCursor = null,
            )
            assertEquals(Decision.ACCEPT, actual(input), "input=$input")
        }
    }

    @Test
    fun `direct call with blank expected contact accepts any sender`() {
        repeat(100) {
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "",
                fromUserId = listOf("a", "stranger", "bob")[random.nextInt(3)],
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                lastAcceptedCursor = null,
            )
            assertEquals(Decision.ACCEPT, actual(input), "input=$input")
        }
    }

    @Test
    fun `offer variants busy-reject on call id mismatch`() {
        for (type in offerVariants) {
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-2",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = type,
                lastAcceptedCursor = null,
            )
            assertEquals(Decision.BUSY_REJECT, actual(input), "input=$input")
        }
    }

    @Test
    fun `non-offer types drop on call id mismatch`() {
        for (type in nonOfferTypes) {
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-2",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = type,
                lastAcceptedCursor = null,
            )
            assertEquals(Decision.DROP, actual(input), "input=$input")
        }
    }

    @Test
    fun `metadata mismatch busy-rejects offer and drops others`() {
        for (type in offerVariants) {
            val input = randomInput().copy(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "g1",
                incomingGroupId = "g2",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("a", "b"),
                signalType = type,
                lastAcceptedCursor = null,
            )
            assertEquals(Decision.BUSY_REJECT, actual(input), "input=$input")
        }
        for (type in nonOfferTypes) {
            val input = randomInput().copy(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "g1",
                incomingGroupId = "g2",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("a", "b"),
                signalType = type,
                lastAcceptedCursor = null,
            )
            assertEquals(Decision.DROP, actual(input), "input=$input")
        }
    }

    @Test
    fun `member set order and duplicates do not change the decision`() {
        repeat(100) {
            val members = listOf("a", "b", "c").shuffled(random).take(random.nextInt(0, 4))
            val base = randomInput().copy(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "g1",
                incomingGroupId = "g1",
                activeMembers = members,
                incomingMembers = members,
            )
            val shuffled = base.copy(
                activeMembers = (members + members).shuffled(random),
                incomingMembers = members.reversed(),
            )
            assertEquals(actual(base), actual(shuffled), "base=$base shuffled=$shuffled")
        }
    }

    @Test
    fun `member set difference fails metadata`() {
        repeat(50) {
            val type = if (random.nextBoolean()) "offer" else "answer"
            val input = randomInput().copy(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = "g1",
                incomingGroupId = "g1",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("a", "c"),
                signalType = type,
                lastAcceptedCursor = null,
            )
            val expected = if (type == "offer") Decision.BUSY_REJECT else Decision.DROP
            assertEquals(expected, actual(input), "input=$input")
        }
    }

    @Test
    fun `blank group id bypasses member mismatch`() {
        repeat(100) {
            val input = randomInput().copy(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "b",
                activeCallId = "",
                incomingCallId = "call-1",
                activeGroupId = if (random.nextBoolean()) "" else "g1",
                incomingGroupId = if (random.nextBoolean()) "" else "g1",
                activeMembers = listOf("a"),
                incomingMembers = listOf("x", "y", "z"),
                signalType = "offer",
                lastAcceptedCursor = null,
            )
            // 至少一侧群 id 为空时元数据门必过；另一侧取值由随机决定，
            // 故只在确实一侧为空时断言放行。
            if (input.activeGroupId.isBlank() || input.incomingGroupId.isBlank()) {
                assertEquals(Decision.ACCEPT, actual(input), "input=$input")
            }
        }
    }

    @Test
    fun `legacy zero cursor is admitted against any last accepted cursor`() {
        repeat(100) {
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = 0L,
                incomingSequence = 0L,
                lastAcceptedCursor = randomCursor(),
            )
            assertEquals(Decision.ACCEPT, actual(input), "input=$input")
        }
    }

    @Test
    fun `equal cursor is admitted as benign repeat`() {
        repeat(100) {
            val cursor = randomCursor()
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = cursor.epoch,
                incomingSequence = cursor.sequence,
                lastAcceptedCursor = cursor,
            )
            assertEquals(Decision.ACCEPT, actual(input), "input=$input")
        }
    }

    @Test
    fun `stale cursor is dropped`() {
        repeat(100) {
            val epoch = random.nextLong(1, 6)
            val sequence = random.nextLong(0, 100)
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = epoch,
                incomingSequence = sequence,
                lastAcceptedCursor = Cursor(epoch, sequence + 1),
            )
            assertEquals(Decision.DROP, actual(input), "input=$input")
        }
        repeat(50) {
            val epoch = random.nextLong(1, 6)
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = epoch,
                incomingSequence = 0L,
                lastAcceptedCursor = Cursor(epoch + 1, 0L),
            )
            assertEquals(Decision.DROP, actual(input), "input=$input")
        }
    }

    @Test
    fun `higher epoch wins over lower sequence`() {
        repeat(100) {
            val epoch = random.nextLong(1, 6)
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = epoch + 1,
                incomingSequence = Long.MIN_VALUE,
                lastAcceptedCursor = Cursor(epoch, Long.MAX_VALUE),
            )
            assertEquals(Decision.ACCEPT, actual(input), "input=$input")
        }
    }

    @Test
    fun `null last accepted cursor accepts any non-legacy cursor`() {
        repeat(100) {
            val cursor = randomCursor()
            if (cursor.epoch == 0L && cursor.sequence == 0L) return@repeat
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = cursor.epoch,
                incomingSequence = cursor.sequence,
                lastAcceptedCursor = null,
            )
            assertEquals(Decision.ACCEPT, actual(input), "input=$input")
        }
    }

    @Test
    fun `sender gate precedes busy reject`() {
        repeat(50) {
            val blankSender = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "",
                activeCallId = "call-1",
                incomingCallId = "call-2",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "offer",
                lastAcceptedCursor = null,
            )
            assertEquals(Decision.DROP, actual(blankSender), "input=$blankSender")
            val foreignSender = blankSender.copy(fromUserId = "b")
            assertEquals(Decision.DROP, actual(foreignSender), "input=$foreignSender")
        }
    }

    @Test
    fun `cursor monotonicity flips at most once`() {
        repeat(20) {
            val last = Cursor(3L, 5L)
            val base = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                lastAcceptedCursor = last,
            )
            // sequence 0..10 扫过阈值 5：DROP…DROP ACCEPT…ACCEPT，至多一次翻转。
            val sequenceDecisions = (0L..10L).map { seq ->
                actual(base.copy(incomingEpoch = 3L, incomingSequence = seq))
            }
            val flips = sequenceDecisions.zipWithNext().count { (a, b) -> a != b }
            assertEquals(1, flips, "sequenceDecisions=$sequenceDecisions")
            assertTrue(sequenceDecisions.take(5).all { it == Decision.DROP })
            assertTrue(sequenceDecisions.drop(5).all { it == Decision.ACCEPT })
            // epoch 2 全 DROP、epoch 4 全 ACCEPT。
            assertTrue((0L..10L).all { seq ->
                actual(base.copy(incomingEpoch = 2L, incomingSequence = seq)) == Decision.DROP
            })
            assertTrue((0L..10L).all { seq ->
                actual(base.copy(incomingEpoch = 4L, incomingSequence = seq)) == Decision.ACCEPT
            })
        }
    }

    @Test
    fun `extreme cursors follow epoch-first compare`() {
        val cases = listOf(
            Triple(Cursor(Long.MAX_VALUE, Long.MAX_VALUE), Cursor(Long.MAX_VALUE, Long.MAX_VALUE - 1), Decision.ACCEPT),
            Triple(Cursor(Long.MAX_VALUE, Long.MIN_VALUE), Cursor(Long.MAX_VALUE, Long.MAX_VALUE), Decision.DROP),
            Triple(Cursor(Long.MIN_VALUE, Long.MIN_VALUE), Cursor(Long.MIN_VALUE, Long.MIN_VALUE + 1), Decision.DROP),
            Triple(Cursor(Long.MIN_VALUE, Long.MAX_VALUE), Cursor(Long.MAX_VALUE, Long.MIN_VALUE), Decision.DROP),
            Triple(Cursor(Long.MAX_VALUE, Long.MIN_VALUE), Cursor(Long.MIN_VALUE, Long.MAX_VALUE), Decision.ACCEPT),
            Triple(Cursor(Long.MIN_VALUE, Long.MIN_VALUE), Cursor(Long.MIN_VALUE, Long.MIN_VALUE), Decision.ACCEPT),
        )
        for ((incoming, last, expected) in cases) {
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-1",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = "answer",
                incomingEpoch = incoming.epoch,
                incomingSequence = incoming.sequence,
                lastAcceptedCursor = last,
            )
            assertEquals(expected, actual(input), "incoming=$incoming last=$last")
        }
    }

    @Test
    fun `empty incoming call id cannot bind an active call`() {
        repeat(50) {
            val active = listOf("call-1", "call-2", "call-9")[random.nextInt(3)]
            val type = if (random.nextBoolean()) "offer" else "answer"
            val input = randomInput().copy(
                isGroupCall = false,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = active,
                incomingCallId = "",
                activeGroupId = "",
                incomingGroupId = "",
                activeMembers = emptyList(),
                incomingMembers = emptyList(),
                signalType = type,
                lastAcceptedCursor = null,
            )
            val expected = if (type == "offer") Decision.BUSY_REJECT else Decision.DROP
            assertEquals(expected, actual(input), "input=$input")
        }
    }

    @Test
    fun `group metadata match still checks call id`() {
        repeat(50) {
            val type = if (random.nextBoolean()) "offer" else "answer"
            val input = randomInput().copy(
                isGroupCall = true,
                expectedContactId = "a",
                fromUserId = "a",
                activeCallId = "call-1",
                incomingCallId = "call-2",
                activeGroupId = "g1",
                incomingGroupId = "g1",
                activeMembers = listOf("a", "b"),
                incomingMembers = listOf("b", "a"),
                signalType = type,
                lastAcceptedCursor = null,
            )
            val expected = if (type == "offer") Decision.BUSY_REJECT else Decision.DROP
            assertEquals(expected, actual(input), "input=$input")
        }
    }
}
