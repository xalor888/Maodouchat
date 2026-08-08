package com.maodouchat.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationIntentPolicyTest {
    @Test
    fun onlyCurrentAccountCanConsumeNotificationIntent() {
        assertTrue(NotificationIntentPolicy.belongsToCurrentAccount("u1", "u1", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", "u2", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount(null, "u1", false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", null, false))
        assertFalse(NotificationIntentPolicy.belongsToCurrentAccount("u1", "u1", true))
    }
}
