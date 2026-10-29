package com.maodouchat.notification

import com.maodouchat.util.NotificationPrivacyPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPrivacyPolicyTest {
    @Test
    fun `app lock always hides sensitive notification details`() {
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = true))
    }

    @Test
    fun `preview preference is respected without app lock`() {
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = false))
        assertFalse(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = true))
    }

    @Test
    fun `app lock hides details even when preview is off`() {
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = false))
    }

    @Test
    fun `only the unlocked preview-on state reveals details`() {
        val combos = listOf(true to true, true to false, false to true, false to false)
        val revealed = combos.filterNot { (lock, preview) ->
            NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = lock, previewEnabled = preview)
        }
        assertEquals(listOf(false to true), revealed)
    }

    @Test
    fun `engaging app lock never reveals details`() {
        for (preview in listOf(true, false)) {
            val before = NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = preview)
            val after = NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = preview)
            assertTrue(!before || after)
        }
    }

    @Test
    fun `without app lock hiding is exactly preview off`() {
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = false))
        assertFalse(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = true))
    }

    @Test
    fun `decision is stable across repeated evaluations`() {
        repeat(3) {
            assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = true))
            assertFalse(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = true))
        }
    }

    @Test
    fun `lock alone and preview off alone are each sufficient to hide`() {
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = true))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = false))
    }

    @Test
    fun `revealing requires unlocked and preview on jointly`() {
        assertFalse(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = true))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = true))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = false))
    }

    @Test
    fun `full decision table is stable on repeat`() {
        val combos = listOf(true to true, true to false, false to true, false to false)
        repeat(2) {
            val hidden = combos.map { (lock, preview) ->
                NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = lock, previewEnabled = preview)
            }
            assertEquals(listOf(true, true, false, true), hidden)
        }
    }

    @Test
    fun `preview off hides under either lock state`() {
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = false))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = false))
    }

    @Test
    fun `lock hides under either preview state`() {
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = true))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = false))
    }

    @Test
    fun `lock masks preview toggle`() {
        // 上锁时预览开关拨动不改变隐藏结论；未上锁时拨动会翻转结论。
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = true))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = false))
        assertFalse(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = true))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = false))
    }

    @Test
    fun `lock toggle matters only when preview is on`() {
        // 上锁/解锁只在预览开启时改变结论；预览关闭时结论恒为隐藏。
        assertFalse(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = true))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = true))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = false))
        assertTrue(NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = false))
    }

    @Test
    fun `hide matches lock-or-not-preview formula`() {
        // 隐藏结论逐字等于 lock || !preview：四格公式级钉住。
        for (lock in listOf(true, false)) {
            for (preview in listOf(true, false)) {
                assertEquals(
                    lock || !preview,
                    NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = lock, previewEnabled = preview),
                    "($lock, $preview)"
                )
            }
        }
    }

    @Test
    fun `hide is monotone in lock and antitone in preview`() {
        // 上锁只会从"显示"变"隐藏"；开预览只会从"隐藏"变"显示"，方向永不反转。
        for (preview in listOf(true, false)) {
            assertTrue(
                !NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = false, previewEnabled = preview) ||
                    NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = true, previewEnabled = preview)
            )
        }
        for (lock in listOf(true, false)) {
            // 开预览永不新增隐藏：预览开时隐藏，则预览关时也隐藏。
            assertTrue(
                !NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = lock, previewEnabled = true) ||
                    NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = lock, previewEnabled = false)
            )
        }
    }

    @Test
    fun `lock toggle sequence flips hide only on preview-on path`() {
        // 连续拨动上锁：预览开时结论跟随翻转（false,true,false），预览关时恒真。
        val onPath = listOf(false, true, false).map { lock ->
            NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = lock, previewEnabled = true)
        }
        assertEquals(listOf(false, true, false), onPath)
        val offPath = listOf(false, true, false).map { lock ->
            NotificationPrivacyPolicy.hideSensitiveDetails(appLockEnabled = lock, previewEnabled = false)
        }
        assertEquals(listOf(true, true, true), offPath)
    }

}
