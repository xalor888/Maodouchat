package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr200c(s: String): String {
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

private fun encIds200c(ids: Collection<String>): String =
    ids.joinToString(";") { encStr200c(it) }

// op kind: 0 resetForNewOutgoingCall, 1 resetForNewGroupCall,
// 2 resetForNewIncomingCall, 3 markIncomingSessionOwner。
// kind 0 用 a=peerId；kind 1 用 a=chatId、members=remoteMembers、
// self=selfUserId；kind 2 用 a=contactId、sdp/callId/groupId、
// members=groupMemberIds。
private data class RstOp200c(
    val kind: Int,
    val a: String = "",
    val sdp: String = "",
    val callId: String = "",
    val groupId: String = "",
    val members: List<String> = emptyList(),
    val self: String = "",
)

private data class RstCase200c(
    val owner: String = "",
    val session: Long = 0L,
    val callId: String = "",
    val ops: List<RstOp200c> = emptyList(),
)

// 效应按发生顺序记：E:0 落结束位，B 启会话，N 取新 callId，
// A 置 callId，G 置 groupId，M 置 mesh 成员，S 置 group 成员，
// O 挂 offer，U 读 owner，L 快照 owner。
private fun rstOracle200c(c: RstCase200c): String {
    val effects = mutableListOf<String>()
    val returns = mutableListOf<String>()
    fun begin(peer: String, incoming: Boolean): Long {
        effects += "B:" + encStr200c(peer) + ":" + if (incoming) "1" else "0"
        return c.session
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                effects += "E:0"
                val session = begin(op.a, false)
                effects += "N"
                effects += "A:" + encStr200c(c.callId)
                effects += "G:"
                effects += "M:"
                effects += "U"
                effects += "L:" + encStr200c(c.owner)
                returns += "R:$session"
            }
            1 -> {
                effects += "E:0"
                val session = begin(op.a, false)
                effects += "N"
                effects += "A:" + encStr200c(c.callId)
                effects += "G:" + encStr200c(op.a)
                effects += "M:" + encIds200c((op.members + op.self).sorted())
                returns += "R:$session"
            }
            2 -> {
                effects += "O:" + encStr200c(op.sdp)
                effects += "E:0"
                begin(op.a, true)
                effects += "A:" + encStr200c(op.callId)
                effects += "U"
                val self = c.owner
                val normalized = op.members.filter { it.isNotBlank() }.distinct()
                val isGroup = op.groupId.isNotBlank() &&
                    normalized.size in 2..GroupCallCapabilities.MAX_MESH_MEMBERS &&
                    self in normalized
                effects += "G:" + encStr200c(if (isGroup) op.groupId else "")
                val mesh = if (isGroup) normalized.sorted() else emptyList()
                effects += "M:" + encIds200c(mesh)
                val members = if (isGroup) normalized.filter { it != self }.toSet() else emptySet()
                effects += "S:" + encIds200c(members)
                returns += "R:" + (if (isGroup) "1" else "0") + ":" + encIds200c(members)
            }
            3 -> {
                effects += "U"
                effects += "L:" + encStr200c(c.owner)
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",") + "|" + returns.joinToString(",")
}

private fun rstProd200c(c: RstCase200c): String {
    val effects = mutableListOf<String>()
    val returns = mutableListOf<String>()
    val controller = CallSessionResetController(
        beginCallSession = { peer, incoming ->
            effects += "B:" + encStr200c(peer) + ":" + if (incoming) "1" else "0"
            c.session
        },
        newCallId = { effects += "N"; c.callId },
        setEndingCall = { effects += if (it) "E:1" else "E:0" },
        setActiveCallId = { effects += "A:" + encStr200c(it) },
        setActiveGroupId = { effects += "G:" + encStr200c(it) },
        setMeshGroupMemberIds = { effects += "M:" + encIds200c(it) },
        setGroupMemberIds = { effects += "S:" + encIds200c(it) },
        setPendingOfferSdp = { effects += "O:" + encStr200c(it ?: "") },
        setCallLogOwnerUserId = { effects += "L:" + encStr200c(it) },
        ownerUserId = { effects += "U"; c.owner },
    )
    for (op in c.ops) {
        when (op.kind) {
            0 -> returns += "R:" + controller.resetForNewOutgoingCall(op.a)
            1 -> returns += "R:" + controller.resetForNewGroupCall(op.a, op.members, op.self)
            2 -> {
                val (isGroup, members) = controller.resetForNewIncomingCall(
                    op.a, op.sdp, op.callId, op.groupId, op.members,
                )
                returns += "R:" + (if (isGroup) "1" else "0") + ":" +
                    encIds200c(members.map { it.userId })
            }
            3 -> controller.markIncomingSessionOwner()
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",") + "|" + returns.joinToString(",")
}

private val RST_IDS200c = listOf(
    "", " ", "\t", "\u00a0", "\u0085", "peer1", "chat-9", "u1", "u2", "u3",
    "Alice", "a", "x\ty", "ünïcödé", "10", "2", "A", "Z", "é",
)

private val RST_SESSIONS200c = listOf(0L, 1L, 7L, 42L, 1234567890123L, -1L)

private fun randomMembers200c(r: Random): List<String> {
    val out = mutableListOf<String>()
    repeat(r.nextInt(0, 5)) { out += RST_IDS200c[r.nextInt(RST_IDS200c.size)] }
    return out
}

private fun randomRstOp200c(r: Random): RstOp200c = when (r.nextInt(4)) {
    0 -> RstOp200c(0, a = RST_IDS200c[r.nextInt(RST_IDS200c.size)])
    1 -> RstOp200c(
        1,
        a = RST_IDS200c[r.nextInt(RST_IDS200c.size)],
        members = randomMembers200c(r),
        self = RST_IDS200c[r.nextInt(RST_IDS200c.size)],
    )
    2 -> RstOp200c(
        2,
        a = RST_IDS200c[r.nextInt(RST_IDS200c.size)],
        sdp = RST_IDS200c[r.nextInt(RST_IDS200c.size)],
        callId = RST_IDS200c[r.nextInt(RST_IDS200c.size)],
        groupId = RST_IDS200c[r.nextInt(RST_IDS200c.size)],
        members = randomMembers200c(r),
    )
    else -> RstOp200c(3)
}

private fun randomRstCase200c(r: Random): RstCase200c {
    val ops = mutableListOf<RstOp200c>()
    repeat(r.nextInt(0, 9)) { ops += randomRstOp200c(r) }
    return RstCase200c(
        RST_IDS200c[r.nextInt(RST_IDS200c.size)],
        RST_SESSIONS200c[r.nextInt(RST_SESSIONS200c.size)],
        RST_IDS200c[r.nextInt(RST_IDS200c.size)],
        ops,
    )
}

private fun boundaryRstCase200c(r: Random): RstCase200c {
    val cases = listOf(
        RstCase200c("u1", 7L, "call-1", listOf(RstOp200c(0, a = "peer1"))),
        RstCase200c(
            "u3", 42L, "call-2",
            listOf(RstOp200c(1, a = "chat-9", members = listOf("u1", "u2"), self = "u3")),
        ),
        RstCase200c(
            "u1", 7L, "call-3",
            listOf(
                RstOp200c(
                    2, a = "peer1", sdp = "sdp", callId = "call-9",
                    groupId = "g1", members = listOf("u1", "u2", "u1"),
                ),
            ),
        ),
        RstCase200c(
            "u1", 7L, "call-3",
            listOf(
                RstOp200c(
                    2, a = "peer1", sdp = "sdp", callId = "call-9",
                    groupId = "g1", members = listOf("u1"),
                ),
            ),
        ),
        RstCase200c(
            "u1", 7L, "call-3",
            listOf(
                RstOp200c(
                    2, a = "peer1", sdp = "sdp", callId = "call-9",
                    groupId = "g1",
                    members = listOf("u1", "u2", "u3", "u4", "u5", "u6", "u7"),
                ),
            ),
        ),
        RstCase200c(
            "u1", 7L, "call-3",
            listOf(
                RstOp200c(
                    2, a = "peer1", sdp = "sdp", callId = "call-9",
                    groupId = "g1",
                    members = listOf(" ", "u1", "u1", "\u00a0", "u2", "\u0085"),
                ),
            ),
        ),
        RstCase200c(
            "u1", 7L, "call-3",
            listOf(
                RstOp200c(
                    2, a = "peer1", sdp = "sdp", callId = "call-9",
                    groupId = "", members = listOf("u1", "u2"),
                ),
            ),
        ),
        RstCase200c(
            "u9", 7L, "call-3",
            listOf(
                RstOp200c(
                    2, a = "peer1", sdp = "sdp", callId = "call-9",
                    groupId = "g1", members = listOf("u1", "u2"),
                ),
            ),
        ),
        RstCase200c("u1", 7L, "call-4", listOf(RstOp200c(3))),
        RstCase200c(
            "u1", 7L, "call-5",
            listOf(
                RstOp200c(0, a = "peer1"),
                RstOp200c(1, a = "chat-9", members = listOf("u2"), self = "u1"),
                RstOp200c(2, a = "peer2", sdp = "", callId = "c", groupId = "g", members = listOf("u1", "u2")),
                RstOp200c(3),
            ),
        ),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class CallSessionResetFuzzTest {

    @Test
    fun rstDifferential200() {
        val r = Random(200301)
        repeat(300) {
            val c = randomRstCase200c(r)
            assertEquals(rstOracle200c(c), rstProd200c(c))
        }
    }

    @Test
    fun rstBoundaryDifferential200() {
        val r = Random(200302)
        repeat(300) {
            val c = boundaryRstCase200c(r)
            assertEquals(rstOracle200c(c), rstProd200c(c))
        }
    }

    @Test
    fun rstPinned200() {
        // 呼出：固定回调顺序，返回固定 session。
        val out = RstCase200c("u1", 7L, "call-1", listOf(RstOp200c(0, a = "peer1")))
        assertEquals(rstOracle200c(out), rstProd200c(out))
        // 呼入成组：3 人含自己、groupId 非空，mesh 取排序后全员。
        val grouped = RstCase200c(
            "u1", 7L, "call-3",
            listOf(
                RstOp200c(
                    2, a = "peer1", sdp = "sdp", callId = "call-9",
                    groupId = "g1", members = listOf("u2", "u1"),
                ),
            ),
        )
        assertEquals(rstOracle200c(grouped), rstProd200c(grouped))
        // 呼入不成组：只有自己一人，mesh 与 group 成员均空。
        val solo = RstCase200c(
            "u1", 7L, "call-3",
            listOf(
                RstOp200c(
                    2, a = "peer1", sdp = "sdp", callId = "call-9",
                    groupId = "g1", members = listOf("u1"),
                ),
            ),
        )
        assertEquals(rstOracle200c(solo), rstProd200c(solo))
        // 空白与重复成员先过滤再判组：U+00A0 是空白、U+0085 不是。
        val blanks = RstCase200c(
            "u1", 7L, "call-3",
            listOf(
                RstOp200c(
                    2, a = "peer1", sdp = "sdp", callId = "call-9",
                    groupId = "g1",
                    members = listOf(" ", "u1", "u1", "\u00a0", "u2", "\u0085"),
                ),
            ),
        )
        assertEquals(rstOracle200c(blanks), rstProd200c(blanks))
    }
}
