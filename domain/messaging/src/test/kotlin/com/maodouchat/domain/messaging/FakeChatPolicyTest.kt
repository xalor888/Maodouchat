package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeChatPolicyTest {
    @Test
    fun `disabled never intercepts`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = false,
                hasPin = true,
                unlockedForCurrentUser = false,
                relockOnBackground = true,
                backgroundAtMillis = 0L
            )
        )
    }

    @Test
    fun `enabled without pin never intercepts`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = false,
                unlockedForCurrentUser = false,
                relockOnBackground = true,
                backgroundAtMillis = 0L
            )
        )
    }

    @Test
    fun `armed with pin intercepts cold start`() {
        assertTrue(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = false,
                relockOnBackground = true,
                backgroundAtMillis = 0L
            )
        )
    }

    @Test
    fun `unlocked session stays open until background when relock enabled`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = true,
                relockOnBackground = true,
                backgroundAtMillis = 0L
            )
        )
        assertTrue(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = true,
                relockOnBackground = true,
                backgroundAtMillis = 10_000L
            )
        )
    }

    @Test
    fun `disabled overrides an unlocked backgrounded session`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = false,
                hasPin = true,
                unlockedForCurrentUser = true,
                relockOnBackground = true,
                backgroundAtMillis = 10_000L
            )
        )
    }

    @Test
    fun `locked session intercepts regardless of relock or background`() {
        assertTrue(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = false,
                relockOnBackground = false,
                backgroundAtMillis = 10_000L
            )
        )
    }

    @Test
    fun `negative background timestamp keeps unlocked session open`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = true,
                relockOnBackground = true,
                backgroundAtMillis = -1L
            )
        )
    }

    @Test
    fun `background timestamp of one millisecond intercepts`() {
        assertTrue(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = true,
                relockOnBackground = true,
                backgroundAtMillis = 1L
            )
        )
    }

    @Test
    fun `decision is stable across repeated calls`() {
        fun decide() = FakeChatPolicy.shouldShowFake(
            enabled = true,
            hasPin = true,
            unlockedForCurrentUser = true,
            relockOnBackground = true,
            backgroundAtMillis = 10_000L
        )
        val first = decide()
        assertTrue(first)
        assertEquals(first, decide())
    }

    @Test
    fun `unlocked session stays open when relock disabled even after background`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = true,
                relockOnBackground = false,
                backgroundAtMillis = 10_000L
            )
        )
    }

    @Test
    fun `missing pin never intercepts even for a backgrounded locked session`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = false,
                unlockedForCurrentUser = false,
                relockOnBackground = true,
                backgroundAtMillis = 10_000L
            )
        )
    }

    @Test
    fun `disabled and pinless together never intercept`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = false,
                hasPin = false,
                unlockedForCurrentUser = true,
                relockOnBackground = true,
                backgroundAtMillis = 10_000L
            )
        )
    }

    @Test
    fun `huge background timestamp still intercepts an unlocked relocking session`() {
        assertTrue(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = true,
                relockOnBackground = true,
                backgroundAtMillis = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun `locked session intercepts even with relock disabled and zero background timestamp`() {
        assertTrue(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = false,
                relockOnBackground = false,
                backgroundAtMillis = 0L
            )
        )
    }

    @Test
    fun `long min background keeps unlocked relocking session open`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = true,
                relockOnBackground = true,
                backgroundAtMillis = Long.MIN_VALUE
            )
        )
    }

    @Test
    fun `locked session intercepts even with long min background`() {
        assertTrue(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = true,
                unlockedForCurrentUser = false,
                relockOnBackground = true,
                backgroundAtMillis = Long.MIN_VALUE
            )
        )
    }

    @Test
    fun `pinless unlocked backgrounded session never intercepts`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = true,
                hasPin = false,
                unlockedForCurrentUser = true,
                relockOnBackground = true,
                backgroundAtMillis = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun `disabled locked session never intercepts`() {
        assertFalse(
            FakeChatPolicy.shouldShowFake(
                enabled = false,
                hasPin = true,
                unlockedForCurrentUser = false,
                relockOnBackground = true,
                backgroundAtMillis = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun `truth table matches formula for positive background`() {
        for (enabled in listOf(true, false)) {
            for (hasPin in listOf(true, false)) {
                for (unlocked in listOf(true, false)) {
                    for (relock in listOf(true, false)) {
                        val expected = enabled && hasPin && (!unlocked || relock)
                        assertEquals(
                            expected,
                            FakeChatPolicy.shouldShowFake(
                                enabled = enabled,
                                hasPin = hasPin,
                                unlockedForCurrentUser = unlocked,
                                relockOnBackground = relock,
                                backgroundAtMillis = 10_000L
                            )
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `pin must be 4 to 12 digits`() {
        assertTrue(FakeChatPolicy.isPinValid("1234"))
        assertTrue(FakeChatPolicy.isPinValid("123456789012"))
        assertFalse(FakeChatPolicy.isPinValid("123"))
        assertFalse(FakeChatPolicy.isPinValid("1234567890123"))
        assertFalse(FakeChatPolicy.isPinValid("12a4"))
        assertFalse(FakeChatPolicy.isPinValid(""))
    }

    @Test
    fun `default weak pins are rejected`() {
        assertTrue(FakeChatPolicy.isWeakPin("0000"))
        assertTrue(FakeChatPolicy.isWeakPin("1234"))
        assertFalse(FakeChatPolicy.isWeakPin("1235"))
        assertFalse(FakeChatPolicy.isWeakPin("0001"))
    }

    @Test
    fun `encoded pin verifies and wrong pin fails`() {
        val stored = FakeChatPolicy.encodePinHash("4826")
        assertTrue(FakeChatPolicy.verifyPinHash("4826", stored))
        assertFalse(FakeChatPolicy.verifyPinHash("4827", stored))
    }

    @Test
    fun `encoded pin has three colon separated parts`() {
        val parts = FakeChatPolicy.encodePinHash("4826").split(':')
        assertEquals(3, parts.size)
        assertEquals(600_000, parts[0].toInt())
    }

    @Test
    fun `two encodes of the same pin differ by salt but both verify`() {
        val first = FakeChatPolicy.encodePinHash("4826")
        val second = FakeChatPolicy.encodePinHash("4826")
        assertFalse(first == second)
        assertTrue(FakeChatPolicy.verifyPinHash("4826", first))
        assertTrue(FakeChatPolicy.verifyPinHash("4826", second))
    }

    @Test
    fun `malformed stored values never verify`() {
        assertFalse(FakeChatPolicy.verifyPinHash("4826", "not-a-hash"))
        assertFalse(FakeChatPolicy.verifyPinHash("4826", "abc:def"))
        assertFalse(FakeChatPolicy.verifyPinHash("4826", "notanint:AA==:AA=="))
        assertFalse(FakeChatPolicy.verifyPinHash("4826", "600000:!!!:AA=="))
    }
}
