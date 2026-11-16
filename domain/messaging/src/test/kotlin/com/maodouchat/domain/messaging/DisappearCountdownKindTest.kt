package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DisappearCountdownKindTest {

    private val now = 1_000_000_000L

    @Test
    fun `null expiry returns null`() {
        assertNull(disappearCountdownKind(null, now))
    }

    @Test
    fun `zero expiry returns null`() {
        assertNull(disappearCountdownKind(0L, now))
    }

    @Test
    fun `under a minute is seconds`() {
        assertEquals(DisappearCountdownKind.Seconds(45),
            disappearCountdownKind(now + 45_000L, now))
    }

    @Test
    fun `a minute is minutes with remainder`() {
        assertEquals(DisappearCountdownKind.Minutes(1, 0),
            disappearCountdownKind(now + 60_000L, now))
        assertEquals(DisappearCountdownKind.Minutes(1, 30),
            disappearCountdownKind(now + 90_000L, now))
        assertEquals(DisappearCountdownKind.Minutes(59, 59),
            disappearCountdownKind(now + 3_599_000L, now))
    }

    @Test
    fun `an hour is hours with remainder`() {
        assertEquals(DisappearCountdownKind.Hours(1, 0),
            disappearCountdownKind(now + 3_600_000L, now))
        assertEquals(DisappearCountdownKind.Hours(1, 1),
            disappearCountdownKind(now + 3_661_000L, now))
    }

    @Test
    fun `past deadline clamps to zero seconds`() {
        // remainingMs 把过期截止钳到 0，原实现同样返回 0 秒档。
        assertEquals(DisappearCountdownKind.Seconds(0),
            disappearCountdownKind(now - 1_000L, now))
    }
}
