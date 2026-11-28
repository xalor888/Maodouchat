package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// op kind: 0 onHostStartedTick, 1 onHostStopped, 2 onHostDestroyed。
private data class LckOp200b(val kind: Int)

private data class LckCase200b(
    val flagsNeeded: Boolean = false,
    val delayMs: Long = 500L,
    val ops: List<LckOp200b> = emptyList(),
)

// 效应按发生顺序记：A:1/A:0 落旗；R 记 flagsNeeded 读次数；
// T:1 记每次 tick 的返回值（恒 true，也要验）。
private fun lckOracle200b(c: LckCase200b): String {
    val effects = mutableListOf<String>()
    val ticks = mutableListOf<String>()
    var reads = 0
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                reads += 1
                effects += if (c.flagsNeeded) "A:1" else "A:0"
                ticks += "T:1"
            }
            1 -> {
                reads += 1
                effects += if (c.flagsNeeded) "A:1" else "A:0"
            }
            2 -> effects += "A:0"
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",") + "|" + reads + "|" + ticks.joinToString(",")
}

private fun lckProd200b(c: LckCase200b): String {
    val effects = mutableListOf<String>()
    val ticks = mutableListOf<String>()
    var reads = 0
    val controller = CallLockScreenFlagController(
        flagsNeeded = { reads += 1; c.flagsNeeded },
        applyFlags = { effects += if (it) "A:1" else "A:0" },
        delayMs = c.delayMs,
    )
    for (op in c.ops) {
        when (op.kind) {
            0 -> ticks += if (controller.onHostStartedTick()) "T:1" else "T:0"
            1 -> controller.onHostStopped()
            2 -> controller.onHostDestroyed()
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return effects.joinToString(",") + "|" + reads + "|" + ticks.joinToString(",")
}

private val LCK_DELAYS200b = listOf(0L, 1L, 500L, 1000L, -1L, Long.MAX_VALUE)

private fun randomLckCase200b(r: Random): LckCase200b {
    val ops = mutableListOf<LckOp200b>()
    repeat(r.nextInt(0, 9)) { ops += LckOp200b(r.nextInt(3)) }
    return LckCase200b(r.nextBoolean(), LCK_DELAYS200b[r.nextInt(LCK_DELAYS200b.size)], ops)
}

private fun boundaryLckCase200b(r: Random): LckCase200b {
    val seqs = listOf(
        listOf(LckOp200b(0)),
        listOf(LckOp200b(1)),
        listOf(LckOp200b(2)),
        listOf(LckOp200b(0), LckOp200b(1), LckOp200b(2)),
        listOf(LckOp200b(2), LckOp200b(0), LckOp200b(1)),
        listOf(LckOp200b(0), LckOp200b(0), LckOp200b(2), LckOp200b(2)),
        listOf(LckOp200b(1), LckOp200b(1), LckOp200b(0)),
        listOf(LckOp200b(2), LckOp200b(2), LckOp200b(2)),
    )
    return LckCase200b(
        r.nextBoolean(),
        LCK_DELAYS200b[r.nextInt(LCK_DELAYS200b.size)],
        seqs[r.nextInt(seqs.size)],
    )
}

// ---------- tests ----------

class CallLockScreenFlagFuzzTest {

    @Test
    fun lckDifferential200() {
        val r = Random(200201)
        repeat(300) {
            val c = randomLckCase200b(r)
            assertEquals(lckOracle200b(c), lckProd200b(c))
        }
    }

    @Test
    fun lckBoundaryDifferential200() {
        val r = Random(200202)
        repeat(300) {
            val c = boundaryLckCase200b(r)
            assertEquals(lckOracle200b(c), lckProd200b(c))
        }
    }

    @Test
    fun lckPinned200() {
        // 需要旗标时 tick 落旗并返回 true，读一次 flagsNeeded。
        val need = LckCase200b(true, 500L, listOf(LckOp200b(0)))
        assertEquals(lckOracle200b(need), lckProd200b(need))
        // 不需要旗标时 tick 落空旗，返回值仍为 true。
        val noNeed = LckCase200b(false, 500L, listOf(LckOp200b(0)))
        assertEquals(lckOracle200b(noNeed), lckProd200b(noNeed))
        // 销毁不读 flagsNeeded，直接落空旗。
        val destroyed = LckCase200b(true, 500L, listOf(LckOp200b(2)))
        assertEquals(lckOracle200b(destroyed), lckProd200b(destroyed))
        // delayMs 只是构造参数：同序列不同 delayMs 效应相同。
        val slow = LckCase200b(true, Long.MAX_VALUE, listOf(LckOp200b(0), LckOp200b(1)))
        val fast = LckCase200b(true, 0L, listOf(LckOp200b(0), LckOp200b(1)))
        assertEquals(lckOracle200b(slow), lckProd200b(slow))
        assertEquals(lckOracle200b(fast), lckProd200b(fast))
        assertEquals(lckOracle200b(slow), lckOracle200b(fast))
    }
}
