package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// op kind: 0 onHostCreated, 1 onHostStarted, 2 onHostStopped。
private data class FglOp200a(val kind: Int)

private data class FglCase200a(val ops: List<FglOp200a> = emptyList())

// 效应按发生顺序记：F:1/F:0 置前台位，C 清 activeChat，P:1/P:0 发 presence。
private fun fglOracle200a(c: FglCase200a): String {
    val effects = mutableListOf<String>()
    for (op in c.ops) {
        when (op.kind) {
            0 -> effects += "F:1"
            1 -> {
                effects += "F:1"
                effects += "P:1"
            }
            2 -> {
                effects += "C"
                effects += "F:0"
                effects += "P:0"
            }
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",")
}

private fun fglProd200a(c: FglCase200a): String {
    val effects = mutableListOf<String>()
    val controller = AppForegroundLifecycleController(
        setAppInForeground = { effects += if (it) "F:1" else "F:0" },
        clearActiveChatSurface = { effects += "C" },
        sendPresence = { effects += if (it) "P:1" else "P:0" },
    )
    for (op in c.ops) {
        when (op.kind) {
            0 -> controller.onHostCreated()
            1 -> controller.onHostStarted()
            2 -> controller.onHostStopped()
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",")
}

private fun randomFglCase200a(r: Random): FglCase200a {
    val ops = mutableListOf<FglOp200a>()
    repeat(r.nextInt(0, 9)) { ops += FglOp200a(r.nextInt(3)) }
    return FglCase200a(ops)
}

private fun boundaryFglCase200a(r: Random): FglCase200a {
    val seqs = listOf(
        listOf(FglOp200a(0)),
        listOf(FglOp200a(1)),
        listOf(FglOp200a(2)),
        listOf(FglOp200a(0), FglOp200a(1), FglOp200a(2)),
        listOf(FglOp200a(2), FglOp200a(2), FglOp200a(1)),
        listOf(FglOp200a(1), FglOp200a(2), FglOp200a(0), FglOp200a(2)),
        listOf(FglOp200a(0), FglOp200a(0), FglOp200a(1), FglOp200a(1), FglOp200a(2), FglOp200a(2)),
        listOf(FglOp200a(2), FglOp200a(0), FglOp200a(2), FglOp200a(1)),
    )
    return FglCase200a(seqs[r.nextInt(seqs.size)])
}

// ---------- tests ----------

class AppForegroundLifecycleFuzzTest {

    @Test
    fun fglDifferential200() {
        val r = Random(200101)
        repeat(300) {
            val c = randomFglCase200a(r)
            assertEquals(fglOracle200a(c), fglProd200a(c))
        }
    }

    @Test
    fun fglBoundaryDifferential200() {
        val r = Random(200102)
        repeat(300) {
            val c = boundaryFglCase200a(r)
            assertEquals(fglOracle200a(c), fglProd200a(c))
        }
    }

    @Test
    fun fglPinned200() {
        // 创建只置前台位，不清 surface、不发 presence。
        val created = FglCase200a(listOf(FglOp200a(0)))
        assertEquals(fglOracle200a(created), fglProd200a(created))
        // 启动置位并发 presence；停止清 surface、落位、发离线。
        val cycle = FglCase200a(listOf(FglOp200a(1), FglOp200a(2)))
        assertEquals(fglOracle200a(cycle), fglProd200a(cycle))
        // 连续两次停止：效应重复出现两次，不幂等合并。
        val twice = FglCase200a(listOf(FglOp200a(2), FglOp200a(2)))
        assertEquals(fglOracle200a(twice), fglProd200a(twice))
        // 空操作序列：零效应。
        val empty = FglCase200a(emptyList())
        assertEquals(fglOracle200a(empty), fglProd200a(empty))
    }
}
