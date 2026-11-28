package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.session.OwnerSessionSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr202b(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun encIds202b(ids: Collection<String>): String =
    ids.joinToString(";") { encStr202b(it) }

private fun bit202b(b: Boolean): String = if (b) "1" else "0"

// launch 出的协程永不执行：只测同步前缀，异步 Room/回执体不跑。
private val dropDispatcher202b = object : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
}

private data class UnbChat202b(
    val id: String,
    val unread: Int = 0,
    val marked: Boolean = false,
    val archived: Boolean = false,
    val type: String = "DIRECT",
)

private fun encChat202b(c: UnbChat202b): String =
    encStr202b(c.id) + ":" + c.unread + ":" + bit202b(c.marked) + ":" +
        bit202b(c.archived) + ":" + c.type

private fun encChats202b(chats: List<UnbChat202b>): String =
    chats.joinToString(",") { encChat202b(it) }

// op kind: 0 markAllUnreadChatsRead，1 batchMarkReadSelected。
private data class UnbOp202b(val kind: Int)

private data class UnbCase202b(
    val chats: List<UnbChat202b> = emptyList(),
    val owner: String = "me",
    val selected: List<String> = emptyList(),
    val ops: List<UnbOp202b> = emptyList(),
)

private fun unbOracle202b(c: UnbCase202b): String {
    val effects = mutableListOf<String>()
    var chats = c.chats
    for (op in c.ops) {
        val ids = if (op.kind == 0) chats.map { it.id } else c.selected
        if (op.kind == 1 && c.selected.isEmpty()) {
            effects += "SKIP"
            continue
        }
        if (c.owner.isBlank()) {
            effects += "SKIP"
            continue
        }
        val excludeArchived = op.kind == 0
        val targets = chats.filter { chat ->
            (chat.id in ids) &&
                (!excludeArchived || !chat.archived) &&
                (chat.unread > 0 || chat.marked)
        }
        if (targets.isEmpty()) {
            effects += "SKIP"
            continue
        }
        val targetIds = targets.map { it.id }.toSet()
        chats = chats.map { chat ->
            if (chat.id in targetIds) chat.copy(unread = 0, marked = false) else chat
        }
        effects += "UPD:" + encChats202b(chats)
    }
    return effects.joinToString(",")
}

private fun unbProd202b(c: UnbCase202b): String {
    val effects = mutableListOf<String>()
    var chats = c.chats.map { d ->
        Chat(id = d.id, unreadCount = d.unread, markedUnread = d.marked, archived = d.archived, chatType = d.type)
    }
    fun encProdChats(): String = chats.joinToString(",") { chat ->
        encStr202b(chat.id) + ":" + chat.unreadCount + ":" + bit202b(chat.markedUnread) + ":" +
            bit202b(chat.archived) + ":" + chat.chatType
    }
    val dropScope = CoroutineScope(SupervisorJob() + dropDispatcher202b)
    val coordinator = ChatListUnreadBatchCoordinator(
        scope = dropScope,
        getChats = { chats },
        updateChats = { transform ->
            chats = transform(chats)
            effects += "UPD:" + encProdChats()
        },
        getSelectedChatIds = { c.selected.toSet() },
        ownerUserId = { c.owner },
        ownerSession = { OwnerSessionSnapshot(it, 1L) },
        isOwnerSessionCurrent = { true },
        withOwnerRoomWrite = { _, block ->
            block()
            true
        },
        getCachedChat = { null },
        cacheChats = {},
        getLatestIncomingMessage = { _, _ -> null },
        enqueueReadReceipt = { _, _, _ -> },
        cancelMessageNotification = {},
        hasSession = { true },
    )
    for (op in c.ops) {
        val before = effects.size
        when (op.kind) {
            0 -> coordinator.markAllUnreadChatsRead()
            1 -> coordinator.batchMarkReadSelected()
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        if (effects.size == before) effects += "SKIP"
    }
    return effects.joinToString(",")
}

private val UNB_IDS202b = listOf(
    "", " ", "\t", "\u00a0", "c1", "c2", "c3", "c4", "c5", "ghost",
    "a", "x\ty", "ünïcödé", "10", "self",
)

private val UNB_TYPES202b = listOf("DIRECT", "GROUP", "SECRET", "CHANNEL")

private fun randomUnbChat202b(r: Random, id: String): UnbChat202b = UnbChat202b(
    id = id,
    unread = r.nextInt(0, 4),
    marked = r.nextBoolean(),
    archived = r.nextBoolean(),
    type = UNB_TYPES202b[r.nextInt(UNB_TYPES202b.size)],
)

private fun randomUnbCase202b(r: Random): UnbCase202b {
    val ids = listOf("c1", "c2", "c3", "c4", "c5").shuffled(r).take(r.nextInt(0, 6))
    val chats = ids.map { randomUnbChat202b(r, it) }
    val selected = List(r.nextInt(0, 5)) { UNB_IDS202b[r.nextInt(UNB_IDS202b.size)] }
    val ops = mutableListOf<UnbOp202b>()
    repeat(r.nextInt(0, 9)) { ops += UnbOp202b(r.nextInt(2)) }
    return UnbCase202b(
        chats = chats,
        owner = listOf("", " ", "me", "self")[r.nextInt(4)],
        selected = selected,
        ops = ops,
    )
}

private fun boundaryUnbCase202b(r: Random): UnbCase202b {
    val base = listOf(
        UnbChat202b("c1", 2),
        UnbChat202b("c2", 1, type = "SECRET"),
        UnbChat202b("c3", 3, archived = true),
        UnbChat202b("c4", 0, marked = true),
        UnbChat202b("c5", 0),
    )
    val cases = listOf(
        UnbCase202b(base, "me", emptyList(), listOf(UnbOp202b(0))),
        UnbCase202b(base, "me", listOf("c1", "c2"), listOf(UnbOp202b(1))),
        UnbCase202b(base, "me", listOf("c3"), listOf(UnbOp202b(1))),
        UnbCase202b(base, "me", emptyList(), listOf(UnbOp202b(1))),
        UnbCase202b(base, "", emptyList(), listOf(UnbOp202b(0))),
        UnbCase202b(base, " ", listOf("c1"), listOf(UnbOp202b(1))),
        UnbCase202b(base, "me", listOf("ghost"), listOf(UnbOp202b(1))),
        UnbCase202b(listOf(UnbChat202b("c5", 0)), "me", listOf("c5"), listOf(UnbOp202b(1))),
        UnbCase202b(base, "me", listOf("c1", "c2", "c3", "c4", "c5"), listOf(UnbOp202b(1), UnbOp202b(0))),
        UnbCase202b(base, "me", listOf("c1"), listOf(UnbOp202b(1), UnbOp202b(1))),
        UnbCase202b(emptyList(), "me", listOf("c1"), listOf(UnbOp202b(0))),
        UnbCase202b(base, "me", emptyList(), emptyList()),
        UnbCase202b(listOf(UnbChat202b("s1", 1, type = "SECRET", archived = true)), "me", listOf("s1"), listOf(UnbOp202b(0))),
        UnbCase202b(listOf(UnbChat202b("s1", 1, type = "SECRET", archived = true)), "me", listOf("s1"), listOf(UnbOp202b(1))),
        UnbCase202b(listOf(UnbChat202b("g1", 2, type = "GROUP")), "me", listOf("g1"), listOf(UnbOp202b(1))),
        UnbCase202b(base, "me", listOf("c1", "c1", "c2"), listOf(UnbOp202b(1))),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatListUnreadBatchCoordinatorFuzzTest {

    @Test
    fun unbDifferential202() {
        val r = Random(202201)
        repeat(300) {
            val c = randomUnbCase202b(r)
            assertEquals(unbOracle202b(c), unbProd202b(c))
        }
    }

    @Test
    fun unbBoundaryDifferential202() {
        val r = Random(202202)
        repeat(300) {
            val c = boundaryUnbCase202b(r)
            assertEquals(unbOracle202b(c), unbProd202b(c))
        }
    }

    @Test
    fun unbPinned202() {
        val base = listOf(
            UnbChat202b("c1", 2),
            UnbChat202b("c2", 1, type = "SECRET"),
            UnbChat202b("c3", 3, archived = true),
        )
        // 全部已读：归档会话排除，普通+密聊归零。
        val all = UnbCase202b(base, "me", emptyList(), listOf(UnbOp202b(0)))
        assertEquals(unbOracle202b(all), unbProd202b(all))
        // 多选已读：只碰选中会话，归档也照读。
        val sel = UnbCase202b(base, "me", listOf("c3"), listOf(UnbOp202b(1)))
        assertEquals(unbOracle202b(sel), unbProd202b(sel))
        // 空选是 noop。
        val empty = UnbCase202b(base, "me", emptyList(), listOf(UnbOp202b(1)))
        assertEquals(unbOracle202b(empty), unbProd202b(empty))
        // owner 为空：整批跳过。
        val noOwner = UnbCase202b(base, "", emptyList(), listOf(UnbOp202b(0)))
        assertEquals(unbOracle202b(noOwner), unbProd202b(noOwner))
        // 已读会话无目标：跳过。
        val read = UnbCase202b(listOf(UnbChat202b("c5", 0)), "me", listOf("c5"), listOf(UnbOp202b(1)))
        assertEquals(unbOracle202b(read), unbProd202b(read))
        // 两次已读：第二次无目标跳过。
        val twice = UnbCase202b(base, "me", listOf("c1"), listOf(UnbOp202b(1), UnbOp202b(1)))
        assertEquals(unbOracle202b(twice), unbProd202b(twice))
    }
}
