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

}
