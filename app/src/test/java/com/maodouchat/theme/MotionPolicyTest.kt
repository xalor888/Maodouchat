package com.maodouchat.theme

import com.maodouchat.ui.theme.MotionPolicy
import com.maodouchat.ui.theme.MotionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPolicyTest {
    @Test
    fun `zero animator scale disables animations`() {
        val settings = MotionPolicy.resolve(animatorScale = 0f, transitionScale = 1f)

        assertFalse(settings.animationsEnabled)
        assertEquals(0, settings.duration(280))
    }

    @Test
    fun `zero transition scale disables animations`() {
        assertFalse(MotionPolicy.resolve(animatorScale = 1f, transitionScale = 0f).animationsEnabled)
    }

    @Test
    fun `duration uses the more restrictive system scale`() {
        val settings = MotionPolicy.resolve(animatorScale = 1.5f, transitionScale = 0.5f)

        assertTrue(settings.animationsEnabled)
        assertEquals(100, settings.duration(200))
    }

    @Test
    fun `duration scale is bounded for performance`() {
        val settings = MotionPolicy.resolve(animatorScale = 10f, transitionScale = 10f)

        assertEquals(2f, settings.durationScale)
        assertEquals(400, settings.duration(200))
    }

    @Test
    fun `explicit reduced motion always returns zero duration`() {
        val settings = MotionSettings(animationsEnabled = false, durationScale = 1f)

        assertEquals(0, MotionPolicy.scaledDuration(520, settings))
    }

    @Test
    fun `only first five initial list entries animate`() {
        val settings = MotionSettings(animationsEnabled = true, durationScale = 1f)

        assertTrue(MotionPolicy.shouldAnimateInitialListEntry(0, settings))
        assertTrue(MotionPolicy.shouldAnimateInitialListEntry(4, settings))
        assertFalse(MotionPolicy.shouldAnimateInitialListEntry(5, settings))
        assertFalse(MotionPolicy.shouldAnimateInitialListEntry(-1, settings))
    }

    @Test
    fun `list stagger delay stays bounded after system scaling`() {
        val settings = MotionSettings(animationsEnabled = true, durationScale = 2f)

        assertEquals(0, MotionPolicy.initialListEntryDelay(0, settings))
        assertEquals(64, MotionPolicy.initialListEntryDelay(1, settings))
        assertEquals(160, MotionPolicy.initialListEntryDelay(4, settings))
        assertEquals(0, MotionPolicy.initialListEntryDelay(5, settings))
    }

    @Test
    fun `reduced motion disables initial list entry animation`() {
        val settings = MotionSettings(animationsEnabled = false, durationScale = 0f)

        assertFalse(MotionPolicy.shouldAnimateInitialListEntry(0, settings))
        assertEquals(0, MotionPolicy.initialListEntryDelay(0, settings))
    }

    @Test
    fun `springSpec snaps when animations disabled`() {
        val off = MotionSettings(animationsEnabled = false, durationScale = 0f)
        val on = MotionSettings(animationsEnabled = true, durationScale = 1f)
        // 仅校验类型分流：关闭→snap，开启→非 snap（spring）
        assertTrue(off.springSpec() is androidx.compose.animation.core.SnapSpec)
        assertFalse(on.springSpec() is androidx.compose.animation.core.SnapSpec)
    }

    @Test
    fun `tweenSpec snaps when duration is zero`() {
        val off = MotionSettings(animationsEnabled = false, durationScale = 0f)
        val on = MotionSettings(animationsEnabled = true, durationScale = 1f)
        assertTrue(off.tweenSpec(200) is androidx.compose.animation.core.SnapSpec)
        assertFalse(on.tweenSpec(200) is androidx.compose.animation.core.SnapSpec)
    }
}
