package com.maodouchat.security

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
    fun `unlocked session stays open across background when relock disabled`() {
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
}
