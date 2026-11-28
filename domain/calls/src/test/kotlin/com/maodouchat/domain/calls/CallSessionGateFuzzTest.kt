package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallSessionGateFuzzTest {

    private val random = Random(2026_11_22L)

    // 与生产侧逐行同构的模型门：begin 递增一代；invalidate 仅当会话等于当前代时
    // 把代推进一位；isCurrent 只看代是否相等。
    private class GateModel {
        var generation: Long = 0L

        fun begin(): Long {
            generation += 1L
            return generation
        }

        fun invalidate(session: Long): Boolean {
            return if (generation == session) {
                generation = session + 1L
                true
            } else {
                false
            }
        }

        fun isCurrent(session: Long): Boolean = generation == session
    }

    private fun randomSession(model: GateModel): Long =
        when (random.nextInt(5)) {
            0 -> model.generation
            1 -> model.generation + 1L
            2 -> 0L
            3 -> 1L
            else -> random.nextLong()
        }

    @Test
    fun `random operation sequences match the model gate`() {
        repeat(50) {
            val gate = CallSessionGate()
            val model = GateModel()
            repeat(20) {
                when (random.nextInt(3)) {
                    0 -> {
                        assertEquals(model.begin(), gate.begin())
                    }
                    1 -> {
                        val session = randomSession(model)
                        assertEquals(
                            model.invalidate(session),
                            gate.invalidate(session),
                            "session=$session generation=${model.generation}",
                        )
                    }
                    else -> {
                        val session = randomSession(model)
                        assertEquals(
                            model.isCurrent(session),
                            gate.isCurrent(session),
                            "session=$session generation=${model.generation}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `begin returns strictly increasing generations starting at one`() {
        val gate = CallSessionGate()
        repeat(200) { index ->
            assertEquals(index + 1L, gate.begin(), "index=$index")
        }
    }

    @Test
    fun `invalidate only succeeds for the current session`() {
        repeat(100) {
            val gate = CallSessionGate()
            val model = GateModel()
            val current = model.begin()
            gate.begin()
            // 当前代可失效；失效后代已推进，同一会话再失效必失败。
            assertTrue(gate.invalidate(current), "current=$current")
            assertEquals(model.invalidate(current), true, "current=$current")
            assertFalse(gate.invalidate(current), "current=$current")
            assertEquals(model.invalidate(current), false, "current=$current")
            assertTrue(gate.isCurrent(current + 1L), "current=$current")
        }
    }

    @Test
    fun `stale invalidate leaves the generation unchanged`() {
        val gate = CallSessionGate()
        val first = gate.begin()
        assertFalse(gate.invalidate(first + 1L))
        assertTrue(gate.isCurrent(first))
        assertFalse(gate.invalidate(0L))
        assertTrue(gate.isCurrent(first))
        assertTrue(gate.invalidate(first))
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(first + 1L))
        val second = gate.begin()
        assertEquals(first + 2L, second)
        assertFalse(gate.invalidate(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `random sessions are current exactly when they equal the generation`() {
        val gate = CallSessionGate()
        val model = GateModel()
        repeat(200) {
            if (random.nextInt(4) == 0) {
                model.begin()
                gate.begin()
            }
            val session = randomSession(model)
            assertEquals(
                model.isCurrent(session),
                gate.isCurrent(session),
                "session=$session generation=${model.generation}",
            )
        }
    }
}
