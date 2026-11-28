package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr202a(s: String): String {
    val out = StringBuilder()
    for (ch in s) out.append("%04x".format(ch.code))
    return out.toString()
}

private fun encIds202a(ids: Collection<String>): String =
    ids.joinToString(";") { encStr202a(it) }

private fun bit202a(b: Boolean): String = if (b) "1" else "0"

// op kind: 0 enter，1 exit，2 toggle，3 batchPin，4 batchDelete。
private data class SelOp202a(
    val kind: Int,
    val id: String = "",
)

private data class SelCase202a(
    val initMode: Boolean = false,
    val initSelected: List<String> = emptyList(),
    val ops: List<SelOp202a> = emptyList(),
)

private fun selOracle202a(c: SelCase202a): String {
    val effects = mutableListOf<String>()
    var mode = c.initMode
    // 选中集是 Set：初始列表里的重复 id 在构造时即去重（保持首次出现顺序）。
    val sel = c.initSelected.toSet().toMutableList()
    fun stateEffect() {
        effects += "ST:" + bit202a(mode) + ":" + encIds202a(sel)
    }
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                mode = true
                stateEffect()
            }
            1 -> {
                mode = false
                sel.clear()
                stateEffect()
            }
            2 -> {
                if (op.id in sel) sel.remove(op.id) else sel.add(op.id)
                if (sel.isEmpty()) mode = false
                stateEffect()
            }
            3 -> {
                if (sel.isEmpty()) {
                    effects += "NP"
                } else {
                    for (id in sel) effects += "PIN:" + encStr202a(id)
                    stateEffect()
                }
            }
            4 -> {
                if (sel.isEmpty()) {
                    effects += "NP"
                } else {
                    for (id in sel) effects += "DEL:" + encStr202a(id)
                    mode = false
                    sel.clear()
                    stateEffect()
                }
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",")
}

private fun selProd202a(c: SelCase202a): String {
    val effects = mutableListOf<String>()
    var selection = ChatListSelection(c.initMode, c.initSelected.toSet())
    val pinned = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    val coordinator = ChatListSelectionCoordinator(
        getSelection = { selection },
        updateSelection = { selection = it },
        togglePinned = { pinned += it },
        deleteChat = { deleted += it },
    )
    fun stateEffect() {
        effects += "ST:" + bit202a(selection.selectionMode) + ":" +
            encIds202a(selection.selectedChatIds)
    }
    for (op in c.ops) {
        val pinnedBefore = pinned.size
        val deletedBefore = deleted.size
        when (op.kind) {
            0 -> coordinator.enterSelectionMode()
            1 -> coordinator.exitSelectionMode()
            2 -> coordinator.toggleSelectChat(op.id)
            3 -> coordinator.batchTogglePinSelected()
            4 -> coordinator.batchDeleteSelected()
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
        if (op.kind == 3) {
            if (pinned.size == pinnedBefore) {
                effects += "NP"
            } else {
                for (i in pinnedBefore until pinned.size) effects += "PIN:" + encStr202a(pinned[i])
                stateEffect()
            }
        } else if (op.kind == 4) {
            if (deleted.size == deletedBefore) {
                effects += "NP"
            } else {
                for (i in deletedBefore until deleted.size) effects += "DEL:" + encStr202a(deleted[i])
                stateEffect()
            }
        } else {
            stateEffect()
        }
    }
    return effects.joinToString(",")
}

private val SEL_IDS202a = listOf(
    "", " ", "\t", "\u00a0", "\u0085", "a", "b", "c1", "c2", "chat-9",
    "u1", "u2", "x\ty", "ünïcödé", "10", "2", "A", "Z", "é", "self",
)

private fun randomSelOp202a(r: Random): SelOp202a = when (r.nextInt(5)) {
    0 -> SelOp202a(0)
    1 -> SelOp202a(1)
    2 -> SelOp202a(2, SEL_IDS202a[r.nextInt(SEL_IDS202a.size)])
    3 -> SelOp202a(3)
    else -> SelOp202a(4)
}

private fun randomSelCase202a(r: Random): SelCase202a {
    val init = SEL_IDS202a.shuffled(r).take(r.nextInt(0, 5))
    val ops = mutableListOf<SelOp202a>()
    repeat(r.nextInt(0, 9)) { ops += randomSelOp202a(r) }
    return SelCase202a(
        initMode = r.nextBoolean(),
        initSelected = init,
        ops = ops,
    )
}

private fun boundarySelCase202a(r: Random): SelCase202a {
    val cases = listOf(
        SelCase202a(false, emptyList(), listOf(SelOp202a(0))),
        SelCase202a(false, emptyList(), listOf(SelOp202a(0), SelOp202a(2, "a"))),
        SelCase202a(false, emptyList(), listOf(SelOp202a(2, "a"))),
        SelCase202a(false, emptyList(), listOf(SelOp202a(2, "a"), SelOp202a(2, "a"))),
        SelCase202a(true, listOf("a", "b"), listOf(SelOp202a(2, "a"))),
        SelCase202a(true, listOf("a", "b"), listOf(SelOp202a(2, "a"), SelOp202a(2, "b"))),
        SelCase202a(true, listOf("a", "b"), listOf(SelOp202a(3))),
        SelCase202a(true, listOf("a", "b"), listOf(SelOp202a(4))),
        SelCase202a(false, emptyList(), listOf(SelOp202a(3))),
        SelCase202a(false, emptyList(), listOf(SelOp202a(4))),
        SelCase202a(false, listOf("a"), listOf(SelOp202a(1))),
        SelCase202a(true, listOf("a"), listOf(SelOp202a(0), SelOp202a(2, "b"), SelOp202a(3), SelOp202a(4))),
        SelCase202a(false, emptyList(), listOf(SelOp202a(0), SelOp202a(2, ""), SelOp202a(2, " "), SelOp202a(3))),
        SelCase202a(true, listOf("a", "b", "c1"), listOf(SelOp202a(2, "b"), SelOp202a(4), SelOp202a(2, "a"))),
        SelCase202a(false, emptyList(), emptyList()),
        SelCase202a(true, listOf("a"), listOf(SelOp202a(2, "a"), SelOp202a(2, "a"), SelOp202a(2, "a"))),
    )
    return cases[r.nextInt(cases.size)]
}

// ---------- tests ----------

class ChatListSelectionCoordinatorFuzzTest {

    @Test
    fun selDifferential202() {
        val r = Random(202101)
        repeat(300) {
            val c = randomSelCase202a(r)
            assertEquals(selOracle202a(c), selProd202a(c))
        }
    }

    @Test
    fun selBoundaryDifferential202() {
        val r = Random(202102)
        repeat(300) {
            val c = boundarySelCase202a(r)
            assertEquals(selOracle202a(c), selProd202a(c))
        }
    }

    @Test
    fun selPinned202() {
        // enter 后 toggle：多选开、勾上 a。
        val enterToggle = SelCase202a(false, emptyList(), listOf(SelOp202a(0), SelOp202a(2, "a")))
        assertEquals(selOracle202a(enterToggle), selProd202a(enterToggle))
        // 裸 toggle 不进多选：只换集合、不碰 mode。
        val bareToggle = SelCase202a(false, emptyList(), listOf(SelOp202a(2, "a")))
        assertEquals(selOracle202a(bareToggle), selProd202a(bareToggle))
        // 最后一个取消勾选自动退出多选。
        val lastOff = SelCase202a(true, listOf("a"), listOf(SelOp202a(2, "a")))
        assertEquals(selOracle202a(lastOff), selProd202a(lastOff))
        // 批量置顶按勾选顺序逐个走。
        val batchPin = SelCase202a(true, listOf("a", "b"), listOf(SelOp202a(3)))
        assertEquals(selOracle202a(batchPin), selProd202a(batchPin))
        // 批量删除后退出多选并清空。
        val batchDel = SelCase202a(true, listOf("a", "b"), listOf(SelOp202a(4)))
        assertEquals(selOracle202a(batchDel), selProd202a(batchDel))
        // 空选批量操作是 noop。
        val emptyBatch = SelCase202a(false, emptyList(), listOf(SelOp202a(3), SelOp202a(4)))
        assertEquals(selOracle202a(emptyBatch), selProd202a(emptyBatch))
    }
}
