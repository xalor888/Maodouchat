package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.User
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr209a(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit209a(b: Boolean): String = if (b) "1" else "0"

private fun opt209a(s: String?): String = s?.let { encStr209a(it) } ?: "~"

private data class CipUser209a(
    val id: String,
    val name: String = "",
)

// op kind：0 occupy，1 release，2 pin。
// chatMode：0 返回缓存会话、1 返回 null、2 抛 RuntimeException 带消息、
// 3 抛 RuntimeException 空消息、4 抛 CancellationException。
// acqMode / occMode / relMode：0 成功、1 抛 RuntimeException 带消息、
// 2 抛 RuntimeException 空消息、3 抛 CancellationException。
private data class CipOp209a(
    val kind: Int,
    val target: String = "c1",
    val peer: String? = null,
    val updatePeer: Boolean = false,
    val chatMode: Int = 0,
    val chatId: String = "c1",
    val chatIsGroup: Boolean = false,
    val participants: List<CipUser209a> = emptyList(),
    val acqMode: Int = 0,
    val occMode: Int = 0,
    val relMode: Int = 0,
)

private data class CipCase209a(
    val initialChatId: String = "",
    val initAcqMode: Int = 0,
    val currentUserId: String = "me",
    val ops: List<CipOp209a> = emptyList(),
)

private fun cipOracle209a(c: CipCase209a): String {
    val effects = mutableListOf<String>()
    var leaseGen: Long? = null
    var acqSeq = 0L
    var curOp = CipOp209a(0)
    var curAcqMode = c.initAcqMode
    fun snap(prefix: String) {
        effects += prefix + ":" + (leaseGen?.toString() ?: "~")
    }
    fun throwFor(mode: Int): Nothing {
        when (mode) {
            1 -> throw RuntimeException("boom")
            2 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun acquire(target: String): Lease {
        effects += "CALL:acquire:" + encStr209a(target)
        if (curAcqMode != 0) throwFor(curAcqMode)
        return Lease(acqSeq++)
    }
    fun occupyCall(lease: Lease, target: String, peer: String?, updatePeer: Boolean) {
        effects += "CALL:occupy:" + lease.generation + ":" + encStr209a(target) + ":" +
            opt209a(peer) + ":" + bit209a(updatePeer)
        if (curOp.occMode != 0) throwFor(curOp.occMode)
    }
    fun releaseCall(lease: Lease) {
        effects += "CALL:release:" + lease.generation
        if (curOp.relMode != 0) throwFor(curOp.relMode)
    }
    fun getChat(target: String): Chat? {
        effects += "CALL:getchat:" + encStr209a(target) + ":" + curOp.chatMode
        return when (curOp.chatMode) {
            0 -> Chat(
                id = curOp.chatId,
                participants = curOp.participants.map { User(it.id, it.name) },
                lastMessageTime = 0L,
                isGroup = curOp.chatIsGroup,
            )
            1 -> null
            2 -> throw RuntimeException("boom")
            3 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun occupySessionCipher(targetChatId: String, peerUserId: String? = null, updatePeer: Boolean = false) {
        if (targetChatId.isBlank()) return
        val lease = leaseGen?.let { Lease(it) } ?: acquire(targetChatId).also { leaseGen = it.generation }
        occupyCall(lease, targetChatId, peerUserId, updatePeer)
    }
    fun releaseSessionCipher() {
        val lease = leaseGen?.let { Lease(it) } ?: return
        leaseGen = null
        releaseCall(lease)
    }
    fun pinSessionCipherPeerFromCache(targetChatId: String) {
        val cached = getChat(targetChatId) ?: return
        if (cached.isGroup) {
            occupySessionCipher(cached.id, peerUserId = null, updatePeer = true)
            return
        }
        val peer = cached.participants.firstOrNull { it.id.isNotBlank() && it.id != c.currentUserId }?.id
        if (peer != null) {
            occupySessionCipher(cached.id, peer, updatePeer = true)
        }
    }
    try {
        if (c.initialChatId.isNotBlank()) {
            leaseGen = acquire(c.initialChatId).generation
        }
    } catch (e: Exception) {
        effects += "THROW:" + e.javaClass.simpleName
        return effects.joinToString(",")
    }
    snap("INIT")
    for (op in c.ops) {
        curOp = op
        curAcqMode = op.acqMode
        try {
            when (op.kind) {
                0 -> occupySessionCipher(op.target, op.peer, op.updatePeer)
                1 -> releaseSessionCipher()
                else -> pinSessionCipherPeerFromCache(op.target)
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private fun cipProd209a(c: CipCase209a): String {
    val effects = mutableListOf<String>()
    var leaseGen: Long? = null
    var acqSeq = 0L
    var curOp = CipOp209a(0)
    var curAcqMode = c.initAcqMode
    fun snap(prefix: String) {
        effects += prefix + ":" + (leaseGen?.toString() ?: "~")
    }
    fun throwFor(mode: Int): Nothing {
        when (mode) {
            1 -> throw RuntimeException("boom")
            2 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    fun acquire(target: String): Lease {
        effects += "CALL:acquire:" + encStr209a(target)
        if (curAcqMode != 0) throwFor(curAcqMode)
        return Lease(acqSeq++)
    }
    fun occupyCall(lease: Lease, target: String, peer: String?, updatePeer: Boolean) {
        effects += "CALL:occupy:" + lease.generation + ":" + encStr209a(target) + ":" +
            opt209a(peer) + ":" + bit209a(updatePeer)
        if (curOp.occMode != 0) throwFor(curOp.occMode)
    }
    fun releaseCall(lease: Lease) {
        effects += "CALL:release:" + lease.generation
        if (curOp.relMode != 0) throwFor(curOp.relMode)
    }
    fun getChat(target: String): Chat? {
        effects += "CALL:getchat:" + encStr209a(target) + ":" + curOp.chatMode
        return when (curOp.chatMode) {
            0 -> Chat(
                id = curOp.chatId,
                participants = curOp.participants.map { User(it.id, it.name) },
                lastMessageTime = 0L,
                isGroup = curOp.chatIsGroup,
            )
            1 -> null
            2 -> throw RuntimeException("boom")
            3 -> throw RuntimeException()
            else -> throw CancellationException("cancel-boom")
        }
    }
    val controller = try {
        ChatSessionCipherController(
            initialChatId = c.initialChatId,
            getChatById = { target -> getChat(target) },
            acquireOccupancy = { target ->
                val lease = acquire(target)
                leaseGen = lease.generation
                lease
            },
            occupyOccupancy = { lease, target, peer, updatePeer -> occupyCall(lease, target, peer, updatePeer) },
            releaseOccupancy = { lease ->
                leaseGen = null
                releaseCall(lease)
            },
            currentUserId = { c.currentUserId },
        )
    } catch (e: Exception) {
        effects += "THROW:" + e.javaClass.simpleName
        return effects.joinToString(",")
    }
    snap("INIT")
    for (op in c.ops) {
        curOp = op
        curAcqMode = op.acqMode
        try {
            when (op.kind) {
                0 -> controller.occupySessionCipher(op.target, op.peer, op.updatePeer)
                1 -> controller.releaseSessionCipher()
                else -> runBlocking { controller.pinSessionCipherPeerFromCache(op.target) }
            }
        } catch (e: Exception) {
            effects += "THROW:" + e.javaClass.simpleName
        }
        snap("FIN")
    }
    return effects.joinToString(",")
}

private val CIP_TARGETS209a = listOf("", " ", "c1", "c2", "x\ty")
private val CIP_USERS209a = listOf("u1", "u2", "", " ", "me")

private fun randomCipUser209a(r: Random): CipUser209a = CipUser209a(
    id = CIP_USERS209a[r.nextInt(CIP_USERS209a.size)],
    name = listOf("", "Ann", "Bob")[r.nextInt(3)],
)

private fun randomCipOp209a(r: Random): CipOp209a {
    val kind = listOf(0, 0, 1, 2, 2)[r.nextInt(5)]
    return CipOp209a(
        kind = kind,
        target = CIP_TARGETS209a[r.nextInt(CIP_TARGETS209a.size)],
        peer = listOf<String?>(null, "u1", "", " ", "p1")[r.nextInt(5)],
        updatePeer = r.nextInt(2) == 0,
        chatMode = r.nextInt(5),
        chatId = CIP_TARGETS209a[r.nextInt(CIP_TARGETS209a.size)],
        chatIsGroup = r.nextInt(2) == 0,
        participants = List(r.nextInt(0, 4)) { randomCipUser209a(r) },
        acqMode = r.nextInt(4),
        occMode = r.nextInt(4),
        relMode = r.nextInt(4),
    )
}

private fun randomCipCase209a(r: Random): CipCase209a = CipCase209a(
    initialChatId = CIP_TARGETS209a[r.nextInt(CIP_TARGETS209a.size)],
    initAcqMode = if (r.nextInt(6) == 0) r.nextInt(1, 4) else 0,
    currentUserId = CIP_USERS209a[r.nextInt(CIP_USERS209a.size)],
    ops = List(r.nextInt(0, 9)) { randomCipOp209a(r) },
)

private fun boundaryCipCase209a(r: Random): CipCase209a {
    val base = CipCase209a()
    val one = CipUser209a("u1", "Ann")
    val self = CipUser209a("me", "Me")
    return when (r.nextInt(24)) {
        0 -> base.copy(ops = listOf(CipOp209a(0, target = "")))
        1 -> base.copy(ops = listOf(CipOp209a(0, target = "  ")))
        2 -> base.copy(ops = listOf(CipOp209a(0, target = "c1")))
        3 -> base.copy(ops = listOf(CipOp209a(0, target = "c1"), CipOp209a(0, target = "c2")))
        4 -> base.copy(ops = listOf(CipOp209a(0, target = "c1", peer = "u1", updatePeer = true)))
        5 -> base.copy(ops = listOf(CipOp209a(0, target = "c1", peer = "")))
        6 -> base.copy(ops = listOf(CipOp209a(0, target = "c1", acqMode = 1)))
        7 -> base.copy(ops = listOf(CipOp209a(0, target = "c1", acqMode = 3)))
        8 -> base.copy(ops = listOf(CipOp209a(0, target = "c1", occMode = 1)))
        9 -> base.copy(ops = listOf(CipOp209a(0, target = "c1", occMode = 3)))
        10 -> base.copy(ops = listOf(CipOp209a(1)))
        11 -> base.copy(ops = listOf(CipOp209a(0, target = "c1"), CipOp209a(1)))
        12 -> base.copy(ops = listOf(CipOp209a(0, target = "c1"), CipOp209a(1, relMode = 2)))
        13 -> base.copy(ops = listOf(CipOp209a(0, target = "c1"), CipOp209a(1), CipOp209a(0, target = "c2")))
        14 -> base.copy(ops = listOf(CipOp209a(2, target = "c1", chatMode = 1)))
        15 -> base.copy(ops = listOf(CipOp209a(2, target = "c1", chatMode = 0, chatId = "c1", chatIsGroup = true)))
        16 -> base.copy(ops = listOf(CipOp209a(2, target = "c1", chatMode = 0, chatId = "c1", participants = listOf(one))))
        17 -> base.copy(ops = listOf(CipOp209a(2, target = "c1", chatMode = 0, chatId = "c1", participants = listOf(self))))
        18 -> base.copy(ops = listOf(CipOp209a(2, target = "c1", chatMode = 0, chatId = "c1", participants = listOf(CipUser209a(""), self, one))))
        19 -> base.copy(ops = listOf(CipOp209a(2, target = "c1", chatMode = 2)))
        20 -> base.copy(ops = listOf(CipOp209a(2, target = "c1", chatMode = 4)))
        21 -> base.copy(ops = listOf(CipOp209a(2, target = "c1", chatMode = 0, chatId = "", chatIsGroup = true)))
        22 -> base.copy(initialChatId = "c0", ops = listOf(CipOp209a(0, target = "c1")))
        else -> base.copy(initialChatId = "c0", initAcqMode = 1, ops = listOf(CipOp209a(0, target = "c1")))
    }
}

// ---------- tests ----------

class ChatSessionCipherControllerFuzzTest {

    @Test
    fun randomCases() {
        val r = Random(209105)
        repeat(300) {
            val c = randomCipCase209a(r)
            assertEquals(cipOracle209a(c), cipProd209a(c))
        }
    }

    @Test
    fun boundaryCases() {
        val r = Random(209106)
        repeat(200) {
            val c = boundaryCipCase209a(r)
            assertEquals(cipOracle209a(c), cipProd209a(c))
        }
    }

    @Test
    fun pinnedCases() {
        val one = CipUser209a("u1", "Ann")
        val cases = listOf(
            CipCase209a(ops = listOf(CipOp209a(0, target = "c1"))),
            CipCase209a(ops = listOf(CipOp209a(0, target = "c1"), CipOp209a(0, target = "c2"))),
            CipCase209a(ops = listOf(CipOp209a(0, target = "c1", acqMode = 1))),
            CipCase209a(ops = listOf(CipOp209a(0, target = "c1"), CipOp209a(1))),
            CipCase209a(ops = listOf(CipOp209a(1))),
            CipCase209a(ops = listOf(CipOp209a(2, target = "c1", chatMode = 1))),
            CipCase209a(ops = listOf(CipOp209a(2, target = "c1", chatMode = 0, chatId = "c1", chatIsGroup = true))),
            CipCase209a(ops = listOf(CipOp209a(2, target = "c1", chatMode = 0, chatId = "c1", participants = listOf(one)))),
            CipCase209a(ops = listOf(CipOp209a(2, target = "c1", chatMode = 0, chatId = "c1", participants = listOf(CipUser209a("me"))))),
            CipCase209a(ops = listOf(CipOp209a(2, target = "c1", chatMode = 2))),
            CipCase209a(initialChatId = "c0", ops = listOf(CipOp209a(0, target = "c1"))),
            CipCase209a(initialChatId = "c0", initAcqMode = 1, ops = listOf(CipOp209a(0, target = "c1"))),
            CipCase209a(ops = emptyList()),
        )
        for (c in cases) {
            assertEquals(cipOracle209a(c), cipProd209a(c))
        }
    }
}
