package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallSignalingOrderPolicyFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomCursor(): Cursor =
        Cursor(random.nextLong(), random.nextLong())

    // admit 三段规则的逐字复述：遗留 (0,0) 恒放行；无已接受游标恒放行；
    // 否则 compare < 0 拒绝，其余（含相等）放行。
    private fun oracleAdmit(incoming: Cursor, lastAccepted: Cursor?): Admit {
        if (incoming.epoch == 0L && incoming.sequence == 0L) return Admit.Accept
        if (lastAccepted == null) return Admit.Accept
        return if (CallSignalingOrderPolicy.compare(incoming, lastAccepted) < 0) {
            Admit.RejectStale
        } else {
            Admit.Accept
        }
    }

    @Test
    fun `admit matches verbatim oracle over random cursors`() {
        repeat(200) {
            val incoming = randomCursor()
            val lastAccepted = if (random.nextInt(4) == 0) null else randomCursor()
            assertEquals(
                oracleAdmit(incoming, lastAccepted),
                CallSignalingOrderPolicy.admit(incoming, lastAccepted),
                "incoming=$incoming lastAccepted=$lastAccepted",
            )
        }
    }

    @Test
    fun `compare is a total order`() {
        repeat(200) {
            val a = randomCursor()
            val b = randomCursor()
            val c = randomCursor()
            // 自反
            assertEquals(0, CallSignalingOrderPolicy.compare(a, a))
            // 反对称：符号相反
            val ab = CallSignalingOrderPolicy.compare(a, b)
            val ba = CallSignalingOrderPolicy.compare(b, a)
            assertEquals(-ab.sign(), ba.sign(), "a=$a b=$b")
            // 传递：a <= b 且 b <= c 则 a <= c
            val bc = CallSignalingOrderPolicy.compare(b, c)
            if (ab <= 0 && bc <= 0) {
                assertTrue(
                    CallSignalingOrderPolicy.compare(a, c) <= 0,
                    "a=$a b=$b c=$c",
                )
            }
            // epoch 优先：epoch 不同则符号由 epoch 决定
            if (a.epoch != b.epoch) {
                assertEquals(a.epoch.compareTo(b.epoch).sign(), ab.sign(), "a=$a b=$b")
            }
        }
    }

    @Test
    fun `equal cursors are admitted`() {
        repeat(100) {
            val cursor = randomCursor()
            assertEquals(
                Admit.Accept,
                CallSignalingOrderPolicy.admit(cursor, cursor),
                "cursor=$cursor",
            )
        }
    }

    @Test
    fun `legacy zero cursor is admitted against any last accepted`() {
        repeat(100) {
            assertEquals(
                Admit.Accept,
                CallSignalingOrderPolicy.admit(Cursor(0, 0), randomCursor()),
            )
        }
        assertEquals(
            Admit.Accept,
            CallSignalingOrderPolicy.admit(Cursor(0, 0), null),
        )
    }

    @Test
    fun `admit rejects exactly the cursors compare calls stale`() {
        repeat(200) {
            val incoming = randomCursor()
            val lastAccepted = randomCursor()
            val expected = if (incoming.epoch == 0L && incoming.sequence == 0L) {
                Admit.Accept
            } else if (CallSignalingOrderPolicy.compare(incoming, lastAccepted) < 0) {
                Admit.RejectStale
            } else {
                Admit.Accept
            }
            assertEquals(
                expected,
                CallSignalingOrderPolicy.admit(incoming, lastAccepted),
                "incoming=$incoming lastAccepted=$lastAccepted",
            )
        }
    }

    @Test
    fun `extreme epoch and sequence values behave`() {
        val extremes = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)
        for (epoch in extremes) {
            for (sequence in extremes) {
                val cursor = Cursor(epoch, sequence)
                assertEquals(0, CallSignalingOrderPolicy.compare(cursor, cursor))
                assertEquals(
                    Admit.Accept,
                    CallSignalingOrderPolicy.admit(cursor, cursor),
                    "cursor=$cursor",
                )
            }
        }
        // (0, x) 与 (x, 0) 不是遗留游标：只看 compare 结果
        assertEquals(
            Admit.RejectStale,
            CallSignalingOrderPolicy.admit(Cursor(0, 5), Cursor(1, 0)),
        )
        assertEquals(
            Admit.Accept,
            CallSignalingOrderPolicy.admit(Cursor(1, 0), Cursor(0, 5)),
        )
    }

    private fun Int.sign(): Int = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }
}
