package com.maodouchat.notification

import com.maodouchat.util.NotificationPrivacyPolicy
import kotlin.test.Test
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
}
