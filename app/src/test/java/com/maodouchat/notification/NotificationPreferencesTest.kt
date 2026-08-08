package com.maodouchat.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationPreferencesTest {
    @Test
    fun `preference keys are isolated between accounts`() {
        val first = NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "user-a")
        val second = NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "user-b")

        assertEquals("preview:user-a", first)
        assertNotEquals(first, second)
    }

    @Test
    fun `different settings cannot collide within one account`() {
        val preview = NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "user-a")
        val sound = NotificationPreferences.scopedKey(NotificationPreferences.KEY_SOUND, "user-a")

        assertNotEquals(preview, sound)
    }
}
