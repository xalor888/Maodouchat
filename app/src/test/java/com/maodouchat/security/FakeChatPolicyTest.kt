package com.maodouchat.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
