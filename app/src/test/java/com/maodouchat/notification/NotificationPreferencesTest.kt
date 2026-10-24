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

    @Test
    fun `scoped key never equals the bare global key`() {
        // 迁移语义：全局 key 与账号作用域 key 不能撞，否则旧全局值会污染新账号。
        assertNotEquals(
            NotificationPreferences.KEY_PREVIEW,
            NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "user-a"),
        )
    }

    @Test
    fun `every public key scopes to a distinct value for one account`() {
        val keys = listOf(
            NotificationPreferences.KEY_ENABLE,
            NotificationPreferences.KEY_SOUND,
            NotificationPreferences.KEY_VIBRATION,
            NotificationPreferences.KEY_PREVIEW,
            NotificationPreferences.KEY_RINGTONE,
            NotificationPreferences.KEY_TASK_REMINDERS,
            NotificationPreferences.KEY_DND_ENABLED,
            NotificationPreferences.KEY_PENDING_SYNC,
            NotificationPreferences.KEY_RINGTONE_URI,
            NotificationPreferences.KEY_GROUP_RINGTONE_URI,
            NotificationPreferences.KEY_DND_START,
            NotificationPreferences.KEY_DND_END,
            NotificationPreferences.KEY_DND_START_MINUTE,
            NotificationPreferences.KEY_DND_END_MINUTE,
        )
        val scoped = keys.map { NotificationPreferences.scopedKey(it, "user-a") }
        assertEquals(keys.size, scoped.toSet().size)
    }

    @Test
    fun `scoped keys are disjoint across accounts`() {
        val a = NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "user-a")
        val b = NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "user-b")
        assertNotEquals(a, b)
        val aSound = NotificationPreferences.scopedKey(NotificationPreferences.KEY_SOUND, "user-a")
        assertNotEquals(aSound, b)
    }

    @Test
    fun `empty userId still scopes`() {
        assertEquals("preview:", NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, ""))
    }

    @Test
    fun `scopedKey is deterministic on repeat`() {
        assertEquals(
            NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "user-a"),
            NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "user-a"),
        )
    }

    @Test
    fun `scoped key keeps base before user`() {
        assertEquals(
            "preview:user-a",
            NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "user-a"),
        )
    }

    @Test
    fun `colon inside userId keeps accounts distinct`() {
        val dotted = NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "a:b")
        val plain = NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "a")
        assertEquals("preview:a:b", dotted)
        assertNotEquals(plain, dotted)
    }

    @Test
    fun `blank userId still scopes away from the bare key`() {
        // 空白 userId 不回退成全局 key：旧全局值不能污染它。
        assertNotEquals(
            NotificationPreferences.KEY_PREVIEW,
            NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, " "),
        )
    }

    @Test
    fun `prefs file name is stable`() {
        assertEquals("notification_settings", NotificationPreferences.PREFS_NAME)
    }

    @Test
    fun `userId casing is preserved`() {
        assertEquals(
            "preview:User-A",
            NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "User-A"),
        )
        assertNotEquals(
            NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "user-a"),
            NotificationPreferences.scopedKey(NotificationPreferences.KEY_PREVIEW, "User-A"),
        )
    }
}
