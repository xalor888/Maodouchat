package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr202c(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun bit202c(b: Boolean): String = if (b) "1" else "0"

private fun optBit202c(b: Boolean?): String = when (b) {
    null -> "~"
    true -> "1"
    false -> "0"
}

private data class TogChat202c(
    val id: String,
    val pinnedAt: Long = 0L,
    val muted: Boolean = false,
    val archived: Boolean = false,
    val marked: Boolean = false,
)

// op kind: 0 PIN，1 MUTE，2 ARCHIVE，3 MARKED_UNREAD。
private data class TogOp202c(
    val kind: Int,
    val chatId: String = "",
)

private data class TogCase202c(
    val chats: List<TogChat202c> = emptyList(),
    val flags: List<Boolean> = listOf(true, true, true, true),
    val ops: List<TogOp202c> = emptyList(),
)

private fun togOracle202c(c: TogCase202c): String {
    val effects = mutableListOf<String>()
    for (op in c.ops) {
        if (!c.flags[op.kind]) {
            effects += "ERR:" + encStr202c("disabled")
            continue
        }
        val chat = c.chats.firstOrNull { it.id == op.chatId }
        if (chat == null) {
            effects += "MISS"
            continue
        }
        val pinSign = if (chat.pinnedAt > 0) "P" else "0"
        var optPin = pinSign
        var optMuted = chat.muted
        var optArch = chat.archived
        var optMarked = chat.marked
        var reqPin: Boolean? = null
        var reqMute: Boolean? = null
        var reqArch: Boolean? = null
        var reqMarked: Boolean? = null
        when (op.kind) {
            0 -> {
                val pinning = chat.pinnedAt <= 0
                optPin = if (chat.pinnedAt > 0) "0" else "P"
                reqPin = pinning
            }
            1 -> {
                optMuted = !chat.muted
                reqMute = !chat.muted
            }
            2 -> {
                val archiving = !chat.archived
                optArch = archiving
                if (archiving) optPin = "0"
                reqArch = archiving
                if (archiving) reqPin = false
            }
            3 -> {
                optMarked = !chat.marked
                reqMarked = !chat.marked
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        val upd = "UPD:" + encStr202c(chat.id) + ":" + optPin + ":" +
            bit202c(optMuted) + ":" + bit202c(optArch) + ":" + bit202c(optMarked) + ":" +
            optBit202c(reqPin) + ":" + optBit202c(reqMute) + ":" +
            optBit202c(reqArch) + ":" + optBit202c(reqMarked)
        effects += upd
    }
    return effects.joinToString(",")
}

private fun togProd202c(c: TogCase202c): String {
    val effects = mutableListOf<String>()
    var chats = c.chats.map { d ->
        Chat(id = d.id, pinnedAt = d.pinnedAt, notificationsMuted = d.muted, archived = d.archived, markedUnread = d.marked)
    }
    val coordinator = ChatListSettingsToggleCoordinator(
        getChats = { chats },
        showError = { effects += "ERR:" + encStr202c(it) },
        pinEnabled = { c.flags[0] },
        muteEnabled = { c.flags[1] },
        archiveEnabled = { c.flags[2] },
        markedUnreadEnabled = { c.flags[3] },
        featureDisabledText = { "disabled" },
        updateChatSettings = { chat, optimistic, request ->
            val upd = "UPD:" + encStr202c(chat.id) + ":" +
                (if (optimistic.pinnedAt > 0) "P" else "0") + ":" +
                bit202c(optimistic.notificationsMuted) + ":" +
                bit202c(optimistic.archived) + ":" +
                bit202c(optimistic.markedUnread) + ":" +
                optBit202c(request.pinned) + ":" +
                optBit202c(request.notificationsMuted) + ":" +
                optBit202c(request.archived) + ":" +
                optBit202c(request.markedUnread)
            effects += upd
        },
    )
    for (op in c.ops) {
        val before = effects.size
        when (op.kind) {
            0 -> coordinator.togglePinned(op.chatId)
            1 -> coordinator.toggleNotificationsMuted(op.chatId)
            2 -> coordinator.toggleArchived(op.chatId)
            3 -> coordinator.toggleMarkedUnread(op.chatId)
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        if (effects.size == before) effects += "MISS"
    }
    return effects.joinToString(",")
}

private val TOG_IDS202c = listOf(
    "", " ", "\t", "\u00a0", "a", "b", "c1", "c2", "ghost",
    "x\ty", "ünïcödé", "10", "self",
)

private fun randomTogChat202c(r: Random, id: String): TogChat202c = TogChat202c(
    id = id,
    pinnedAt = if (r.nextBoolean()) 0L else 1000L,
    muted = r.nextBoolean(),
    archived = r.nextBoolean(),
    marked = r.nextBoolean(),
)

private fun randomTogCase202c(r: Random): TogCase202c {
    val ids = listOf("a", "b", "c1", "c2").shuffled(r).take(r.nextInt(1, 5))
    val chats = ids.map { randomTogChat202c(r, it) }
    val flags = List(4) { r.nextBoolean() }
    val ops = mutableListOf<TogOp202c>()
    repeat(r.nextInt(0, 9)) {
        ops += TogOp202c(
            r.nextInt(4),
            TOG_IDS202c[r.nextInt(TOG_IDS202c.size)],
        )
    }
    return TogCase202c(chats = chats, flags = flags, ops = ops)
}

private fun boundaryTogCase202c(r: Random): TogCase202c {
    val base = listOf(
        TogChat202c("a", 0L, false, false, false),
        TogChat202c("b", 1000L, true, false, true),
    )
    val cases = listOf(
        TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(0, "a"))),
        TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(0, "b"))),
        TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(1, "a"))),
        TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(1, "b"))),
        TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(2, "a"))),
        TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(2, "b"))),
        TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(3, "a"))),
        TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(3, "b"))),
        TogCase202c(base, listOf(false, true, true, true), listOf(TogOp202c(0, "a"))),
        TogCase202c(base, listOf(true, false, true, true), listOf(TogOp202c(1, "a"))),
        TogCase202c(base, listOf(true, true, false, true), listOf(TogOp202c(2, "a"))),
        TogCase202c(base, listOf(true, true, true, false), listOf(TogOp202c(3, "a"))),
        TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(0, "missing"))),
        TogCase202c(base, listOf(false, false, false, false), listOf(TogOp202c(0, "a"), TogOp202c(2, "b"))),
        TogCase202c(listOf(TogChat202c("a", 1000L, false, true, false)), listOf(true, true, true, true), listOf(TogOp202c(2, "a"))),
        TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(0, "a"), TogOp202c(2, "a"), TogOp202c(0, "a"))),
        TogCase202c(base, listOf(true, true, true, true), emptyList()),
        TogCase202c(emptyList(), listOf(true, true, true, true), listOf(TogOp202c(0, "a"))),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatListSettingsToggleCoordinatorFuzzTest {

    @Test
    fun togDifferential202() {
        val r = Random(202301)
        repeat(300) {
            val c = randomTogCase202c(r)
            assertEquals(togOracle202c(c), togProd202c(c))
        }
    }

    @Test
    fun togBoundaryDifferential202() {
        val r = Random(202302)
        repeat(300) {
            val c = boundaryTogCase202c(r)
            assertEquals(togOracle202c(c), togProd202c(c))
        }
    }

    @Test
    fun togPinned202() {
        val base = listOf(
            TogChat202c("a", 0L, false, false, false),
            TogChat202c("b", 1000L, true, false, true),
        )
        // 未置顶→置顶：请求 pinned=true，乐观 pinnedAt 变正。
        val pin = TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(0, "a")))
        assertEquals(togOracle202c(pin), togProd202c(pin))
        // 已置顶→取消：请求 pinned=false，乐观 pinnedAt 归零。
        val unpin = TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(0, "b")))
        assertEquals(togOracle202c(unpin), togProd202c(unpin))
        // 归档：置顶清零，请求 archived=true + pinned=false。
        val archive = TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(2, "b")))
        assertEquals(togOracle202c(archive), togProd202c(archive))
        // 已归档→取消归档：置顶保持（本是 0）。
        val unarchive = TogCase202c(
            listOf(TogChat202c("a", 1000L, false, true, false)),
            listOf(true, true, true, true), listOf(TogOp202c(2, "a")),
        )
        assertEquals(togOracle202c(unarchive), togProd202c(unarchive))
        // 开关关：报错、不走更新。
        val disabled = TogCase202c(base, listOf(true, false, true, true), listOf(TogOp202c(1, "a")))
        assertEquals(togOracle202c(disabled), togProd202c(disabled))
        // 查无此会话：静默 noop。
        val missing = TogCase202c(base, listOf(true, true, true, true), listOf(TogOp202c(0, "missing")))
        assertEquals(togOracle202c(missing), togProd202c(missing))
    }
}
