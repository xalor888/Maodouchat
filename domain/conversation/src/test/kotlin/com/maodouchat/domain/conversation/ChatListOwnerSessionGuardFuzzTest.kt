package com.maodouchat.domain.conversation

import com.maodouchat.core.session.OwnerSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr207a(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit207a(b: Boolean): String = if (b) "1" else "0"

// op kind：0 ownerSession() 默认 owner，1 ownerSession(显式 owner)，
// 2 isOwnerSessionCurrent，3 withOwnerRoomWrite。
private data class GrdOp207a(
    val kind: Int,
    val owner: String = "",
    val gen: Long = 0L,
)

private data class GrdCase207a(
    // 0 null，1 ""，2 "me"，3 "u1"，4 "other"。
    val uidMode: Int = 3,
    // 0 null，1 ""，2 "  "，3 "tok"。
    val tokMode: Int = 3,
    val gen: Long = 1L,
    val purge: Boolean = false,
    // 0 跑 block 取其结果，1 不跑 block 直接 false，
    // 2 抛 RuntimeException，3 抛 CancellationException。
    val txMode: Int = 0,
    val ops: List<GrdOp207a> = emptyList(),
)

private fun grdUid207a(mode: Int): String? = when (mode) {
    0 -> null
    1 -> ""
    2 -> "me"
    3 -> "u1"
    else -> "other"
}

private fun grdTok207a(mode: Int): String? = when (mode) {
    0 -> null
    1 -> ""
    2 -> "  "
    else -> "tok"
}

// OwnerSessionPolicy.isCurrent 的逐分支镜像：注意 "me" 恒不 current。
private fun grdIsCurrent207a(
    session: OwnerSessionSnapshot,
    uid: String?,
    tok: String?,
    liveGen: Long,
    purge: Boolean,
): Boolean =
    !purge &&
        session.ownerUserId.isNotBlank() &&
        session.ownerUserId != "me" &&
        uid == session.ownerUserId &&
        !tok.isNullOrBlank() &&
        liveGen == session.sessionGeneration

private fun grdOracle207a(c: GrdCase207a): String {
    val effects = mutableListOf<String>()
    val uid = grdUid207a(c.uidMode)
    val tok = grdTok207a(c.tokMode)
    fun isCurrent(session: OwnerSessionSnapshot): Boolean =
        grdIsCurrent207a(session, uid, tok, c.gen, c.purge)
    for (op in c.ops) {
        try {
            when (op.kind) {
                0 -> {
                    val s = OwnerSessionSnapshot(uid ?: "", c.gen)
                    effects += "SESS:" + encStr207a(s.ownerUserId) + ":" + s.sessionGeneration
                }
                1 -> {
                    val s = OwnerSessionSnapshot(op.owner, c.gen)
                    effects += "SESS:" + encStr207a(s.ownerUserId) + ":" + s.sessionGeneration
                }
                2 -> effects += "CUR:" + bit207a(isCurrent(OwnerSessionSnapshot(op.owner, op.gen)))
                3 -> {
                    val session = OwnerSessionSnapshot(op.owner, op.gen)
                    val ok = when (c.txMode) {
                        0 -> if (isCurrent(session)) {
                            effects += "BLOCK"
                            true
                        } else {
                            false
                        }
                        1 -> false
                        2 -> throw RuntimeException("boom")
                        else -> throw CancellationException("cancel-boom")
                    }
                    effects += "WR:" + bit207a(ok)
                }
                else -> throw IllegalArgumentException("unknown op ${op.kind}")
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        effects += "FIN"
    }
    return effects.joinToString(",")
}

private fun grdProd207a(c: GrdCase207a): String {
    val effects = mutableListOf<String>()
    val uid = grdUid207a(c.uidMode)
    val tok = grdTok207a(c.tokMode)
    val guard = ChatListOwnerSessionGuard(
        currentUserId = { uid },
        currentToken = { tok },
        sessionGeneration = { c.gen },
        isPurgeInProgress = { c.purge },
        withRoomTransaction = { block ->
            when (c.txMode) {
                0 -> block()
                1 -> false
                2 -> throw RuntimeException("boom")
                else -> throw CancellationException("cancel-boom")
            }
        },
    )
    for (op in c.ops) {
        try {
            when (op.kind) {
                0 -> {
                    val s = guard.ownerSession()
                    effects += "SESS:" + encStr207a(s.ownerUserId) + ":" + s.sessionGeneration
                }
                1 -> {
                    val s = guard.ownerSession(op.owner)
                    effects += "SESS:" + encStr207a(s.ownerUserId) + ":" + s.sessionGeneration
                }
                2 -> effects += "CUR:" + bit207a(
                    guard.isOwnerSessionCurrent(OwnerSessionSnapshot(op.owner, op.gen))
                )
                3 -> {
                    val ok = runBlocking {
                        guard.withOwnerRoomWrite(OwnerSessionSnapshot(op.owner, op.gen)) {
                            effects += "BLOCK"
                        }
                    }
                    effects += "WR:" + bit207a(ok)
                }
                else -> throw IllegalArgumentException("unknown op ${op.kind}")
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        effects += "FIN"
    }
    return effects.joinToString(",")
}

private val GRD_IDS207a = listOf("", " ", "u1", "me", "other", "x\ty", "ünïcödé")

private val GRD_GENS207a = listOf(0L, 1L, 2L, -1L, 1_700_000_000_000L)

private fun randomGrdOp207a(r: Random): GrdOp207a {
    val kind = listOf(0, 0, 1, 2, 2, 3, 3)[r.nextInt(7)]
    return GrdOp207a(
        kind = kind,
        owner = GRD_IDS207a[r.nextInt(GRD_IDS207a.size)],
        gen = GRD_GENS207a[r.nextInt(GRD_GENS207a.size)],
    )
}

private fun randomGrdCase207a(r: Random): GrdCase207a = GrdCase207a(
    uidMode = r.nextInt(5),
    tokMode = r.nextInt(4),
    gen = GRD_GENS207a[r.nextInt(GRD_GENS207a.size)],
    purge = r.nextInt(4) == 0,
    txMode = r.nextInt(4),
    ops = List(r.nextInt(0, 9)) { randomGrdOp207a(r) },
)

private fun boundaryGrdCase207a(r: Random): GrdCase207a {
    val base = GrdCase207a()
    return when (r.nextInt(24)) {
        0 -> base.copy(ops = listOf(GrdOp207a(0)))
        1 -> base.copy(ops = listOf(GrdOp207a(0)), uidMode = 0)
        2 -> base.copy(ops = listOf(GrdOp207a(0)), uidMode = 2)
        3 -> base.copy(ops = listOf(GrdOp207a(1, "me")))
        4 -> base.copy(ops = listOf(GrdOp207a(1, "u1")))
        5 -> base.copy(ops = listOf(GrdOp207a(2, "u1", 1L)))
        6 -> base.copy(ops = listOf(GrdOp207a(2, "me", 1L)))
        7 -> base.copy(ops = listOf(GrdOp207a(2, "", 1L)))
        8 -> base.copy(ops = listOf(GrdOp207a(2, "u1", 2L)))
        9 -> base.copy(ops = listOf(GrdOp207a(2, "u1", 1L)), tokMode = 0)
        10 -> base.copy(ops = listOf(GrdOp207a(2, "u1", 1L)), tokMode = 1)
        11 -> base.copy(ops = listOf(GrdOp207a(2, "u1", 1L)), tokMode = 2)
        12 -> base.copy(ops = listOf(GrdOp207a(2, "u1", 1L)), purge = true)
        13 -> base.copy(ops = listOf(GrdOp207a(2, "u1", 1L)), uidMode = 0)
        14 -> base.copy(ops = listOf(GrdOp207a(3, "u1", 1L)), txMode = 0)
        15 -> base.copy(ops = listOf(GrdOp207a(3, "u1", 2L)), txMode = 0)
        16 -> base.copy(ops = listOf(GrdOp207a(3, "u1", 1L)), txMode = 1)
        17 -> base.copy(ops = listOf(GrdOp207a(3, "u1", 1L)), txMode = 2)
        18 -> base.copy(ops = listOf(GrdOp207a(3, "u1", 1L)), txMode = 3)
        19 -> base.copy(ops = listOf(GrdOp207a(2, "other", 1L)), uidMode = 4)
        20 -> base.copy(ops = listOf(GrdOp207a(2, "u1", 1L)), uidMode = 4)
        21 -> base.copy(ops = listOf(GrdOp207a(0), GrdOp207a(2, "u1", 1L)))
        22 -> base.copy(ops = listOf(GrdOp207a(1, "  ")))
        else -> base.copy(ops = emptyList())
    }
}

// ---------- tests ----------

class ChatListOwnerSessionGuardFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(207301)
        repeat(300) {
            val c = randomGrdCase207a(r)
            assertEquals(grdOracle207a(c), grdProd207a(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(207302)
        repeat(200) {
            val c = boundaryGrdCase207a(r)
            assertEquals(grdOracle207a(c), grdProd207a(c))
        }
    }

    @Test
    fun pinnedCases() {
        val cases = listOf(
            GrdCase207a(ops = listOf(GrdOp207a(0))),
            GrdCase207a(ops = listOf(GrdOp207a(0)), uidMode = 0),
            GrdCase207a(ops = listOf(GrdOp207a(0)), uidMode = 2),
            GrdCase207a(ops = listOf(GrdOp207a(2, "u1", 1L))),
            GrdCase207a(ops = listOf(GrdOp207a(2, "me", 1L))),
            GrdCase207a(ops = listOf(GrdOp207a(2, "u1", 1L)), tokMode = 0),
            GrdCase207a(ops = listOf(GrdOp207a(2, "u1", 1L)), purge = true),
            GrdCase207a(ops = listOf(GrdOp207a(3, "u1", 1L)), txMode = 0),
            GrdCase207a(ops = listOf(GrdOp207a(3, "u1", 1L)), txMode = 1),
            GrdCase207a(ops = listOf(GrdOp207a(3, "u1", 1L)), txMode = 2),
            GrdCase207a(ops = listOf(GrdOp207a(3, "u1", 1L)), txMode = 3),
            GrdCase207a(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(grdOracle207a(c), grdProd207a(c))
        }
    }
}
