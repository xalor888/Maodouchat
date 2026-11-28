package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr201a(s: String): String {
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

// phase：0 IDLE、1 OUTGOING_RINGING、2 INCOMING_RINGING。
private class SesMachine201a {
    var epoch: Long = 0L
    var phase: Int = 0

    fun begin(peer: String, incoming: Boolean): Long {
        check(peer.isNotBlank()) { "Call peer is required" }
        check(phase == 0) { "Call session already active" }
        epoch += 1L
        phase = if (incoming) 2 else 1
        return epoch
    }
}

private class SesGate201a {
    var gen: Long = 0L

    fun begin(): Long {
        gen += 1L
        return gen
    }

    fun isCurrent(s: Long): Boolean = gen == s
}

// op kind: 0 beginCallSession，1 isCurrentCallSession，2 newCallId。
// kind 0 用 peer/incoming；kind 1 用 session/mgr（0=null，1..3 哨兵）。
private data class SesOp201a(
    val kind: Int,
    val peer: String = "",
    val incoming: Boolean = false,
    val session: Long = 0L,
    val mgr: Int = 0,
)

private data class SesCase201a(
    val ending: Boolean = false,
    val curMgr: Int = 0,
    val ops: List<SesOp201a> = emptyList(),
)

// 效应按发生顺序记：X 清信令幂等，Y 清入站游标，Z 开出站游标，
// I 复位 ICE 恢复，D 置 domain 会话，A 置 call 会话，E 读 ending，
// M 读当前 manager；返回 R:<gate> / R:0/1 / N:1（新 callId 只验形状）。
private fun sesOracle201a(c: SesCase201a): String {
    val effects = mutableListOf<String>()
    val returns = mutableListOf<String>()
    val machine = SesMachine201a()
    val gate = SesGate201a()
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                effects += "X"
                effects += "Y"
                effects += "Z"
                effects += "I"
                try {
                    val epoch = machine.begin(op.peer, op.incoming)
                    effects += "D:$epoch"
                    val g = gate.begin()
                    effects += "A:$g"
                    returns += "R:$g"
                } catch (e: IllegalStateException) {
                    returns += "THROW:IllegalStateException"
                }
            }
            1 -> {
                val cur = gate.isCurrent(op.session)
                var res = false
                if (cur) {
                    effects += "E:" + if (c.ending) "1" else "0"
                    if (!c.ending) {
                        res = if (op.mgr == 0) {
                            true
                        } else {
                            effects += "M:" + c.curMgr
                            c.curMgr == op.mgr
                        }
                    }
                }
                returns += "R:" + if (res) "1" else "0"
            }
            2 -> returns += "N:1"
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",") + "|" + returns.joinToString(",")
}

private fun sesProd201a(c: SesCase201a): String {
    val effects = mutableListOf<String>()
    val returns = mutableListOf<String>()
    val sentinels = arrayOf(Any(), Any(), Any())
    val controller = CallSessionController(
        callSessionGate = CallSessionGate(),
        callSessionMachine = CallSessionMachine(),
        resetIceRecoveryAttempts = { effects += "I" },
        clearSignalingIdempotency = { effects += "X" },
        clearInboundSignalingCursors = { effects += "Y" },
        beginOutboundSignalingCursor = { effects += "Z" },
        setActiveDomainSession = { effects += "D:$it" },
        setActiveCallSession = { effects += "A:$it" },
        endingCall = {
            effects += "E:" + if (c.ending) "1" else "0"
            c.ending
        },
        currentManager = {
            effects += "M:" + c.curMgr
            if (c.curMgr == 0) null else sentinels[c.curMgr - 1]
        },
    )
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                try {
                    returns += "R:" + controller.beginCallSession(op.peer, op.incoming)
                } catch (e: IllegalStateException) {
                    returns += "THROW:IllegalStateException"
                }
            }
            1 -> {
                val mgr = if (op.mgr == 0) null else sentinels[op.mgr - 1]
                returns += "R:" + if (controller.isCurrentCallSession(op.session, mgr)) "1" else "0"
            }
            2 -> {
                val id = controller.newCallId()
                val ok = id.length == 37 && id.startsWith("call_") &&
                    id.drop(5).all { it in '0'..'9' || it in 'a'..'f' }
                returns += if (ok) "N:1" else "N:0:" + encStr201a(id)
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",") + "|" + returns.joinToString(",")
}

private val SES_IDS201a = listOf(
    "", " ", "\t", "\u00a0", "\u0085", "peer1", "chat-9", "u1", "u2", "u3",
    "Alice", "a", "x\ty", "ünïcödé", "10", "2", "A", "Z", "é",
)

private val SES_SESSIONS201a = listOf(0L, 1L, 2L, 7L, 42L, -1L)

private fun randomSesOp201a(r: Random): SesOp201a = when (r.nextInt(3)) {
    0 -> SesOp201a(0, peer = SES_IDS201a[r.nextInt(SES_IDS201a.size)], incoming = r.nextBoolean())
    1 -> SesOp201a(
        1,
        session = SES_SESSIONS201a[r.nextInt(SES_SESSIONS201a.size)],
        mgr = r.nextInt(0, 4),
    )
    else -> SesOp201a(2)
}

private fun randomSesCase201a(r: Random): SesCase201a {
    val ops = mutableListOf<SesOp201a>()
    repeat(r.nextInt(0, 9)) { ops += randomSesOp201a(r) }
    return SesCase201a(r.nextBoolean(), r.nextInt(0, 4), ops)
}

private fun boundarySesCase201a(r: Random): SesCase201a {
    val cases = listOf(
        SesCase201a(false, 0, listOf(SesOp201a(0, peer = "peer1"))),
        SesCase201a(false, 0, listOf(SesOp201a(0, peer = "peer1", incoming = true))),
        SesCase201a(false, 0, listOf(SesOp201a(0, peer = ""))),
        SesCase201a(false, 0, listOf(SesOp201a(0, peer = " "))),
        SesCase201a(false, 0, listOf(SesOp201a(0, peer = "\u00a0"))),
        SesCase201a(false, 0, listOf(SesOp201a(0, peer = "\u0085"))),
        SesCase201a(
            false, 0,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(0, peer = "peer2")),
        ),
        SesCase201a(
            false, 0,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(1, session = 1L)),
        ),
        SesCase201a(
            false, 0,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(1, session = 0L)),
        ),
        SesCase201a(
            false, 0,
            listOf(SesOp201a(1, session = 0L)),
        ),
        SesCase201a(
            false, 0,
            listOf(SesOp201a(1, session = 1L)),
        ),
        SesCase201a(
            true, 0,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(1, session = 1L)),
        ),
        SesCase201a(
            false, 2,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(1, session = 1L, mgr = 2)),
        ),
        SesCase201a(
            false, 2,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(1, session = 1L, mgr = 1)),
        ),
        SesCase201a(
            false, 0,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(1, session = 1L, mgr = 2)),
        ),
        SesCase201a(
            true, 2,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(1, session = 1L, mgr = 2)),
        ),
        SesCase201a(false, 0, listOf(SesOp201a(2))),
        SesCase201a(
            false, 0,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(2), SesOp201a(1, session = 1L)),
        ),
        SesCase201a(
            false, 3,
            listOf(
                SesOp201a(0, peer = "peer1", incoming = true),
                SesOp201a(1, session = 1L, mgr = 3),
                SesOp201a(1, session = 2L, mgr = 3),
                SesOp201a(2),
            ),
        ),
        SesCase201a(false, 0, emptyList()),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class CallSessionControllerFuzzTest {

    @Test
    fun sesDifferential201() {
        val r = Random(201101)
        repeat(300) {
            val c = randomSesCase201a(r)
            assertEquals(sesOracle201a(c), sesProd201a(c))
        }
    }

    @Test
    fun sesBoundaryDifferential201() {
        val r = Random(201102)
        repeat(300) {
            val c = boundarySesCase201a(r)
            assertEquals(sesOracle201a(c), sesProd201a(c))
        }
    }

    @Test
    fun sesPinned201() {
        // 首个 begin：gate 从 0 抬到 1，返回 1。
        val first = SesCase201a(false, 0, listOf(SesOp201a(0, peer = "peer1")))
        assertEquals(sesOracle201a(first), sesProd201a(first))
        // 空 peer 抛错：四清已发生，D/A 均无。
        val blank = SesCase201a(false, 0, listOf(SesOp201a(0, peer = "")))
        assertEquals(sesOracle201a(blank), sesProd201a(blank))
        // 二次 begin 抛错：会话已激活。
        val twice = SesCase201a(
            false, 0,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(0, peer = "peer2")),
        )
        assertEquals(sesOracle201a(twice), sesProd201a(twice))
        // 新 gate 上 session 0 即当前：0==0。
        val fresh = SesCase201a(false, 0, listOf(SesOp201a(1, session = 0L)))
        assertEquals(sesOracle201a(fresh), sesProd201a(fresh))
        // manager 身份不符：读了当前 manager 才判否。
        val mismatch = SesCase201a(
            false, 2,
            listOf(SesOp201a(0, peer = "peer1"), SesOp201a(1, session = 1L, mgr = 1)),
        )
        assertEquals(sesOracle201a(mismatch), sesProd201a(mismatch))
        // 新 callId 只验形状：call_ + 32 位小写 hex。
        val shape = SesCase201a(false, 0, listOf(SesOp201a(2)))
        assertEquals(sesOracle201a(shape), sesProd201a(shape))
    }
}
