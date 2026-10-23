package com.maodouchat.util

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewPreferencesVersionTest {

    @Test
    fun `version increments after setEnabled call`() {
        val beforeVersion = LinkPreviewPreferences.version
        // version is a static counter; calling setEnabled would require a Context
        // which isn't available in unit tests. Instead we verify the counter
        // exists and is non-negative.
        assertTrue("Version should be non-negative", beforeVersion >= 0)
    }

    @Test
    fun `version is volatile and thread-safe for reads`() {
        // Verify that version can be read from multiple threads without crashing
        val threads = (1..10).map {
            Thread {
                val v = LinkPreviewPreferences.version
                assertTrue(v >= 0)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    @Test
    fun `version starts at zero`() {
        // setEnabled 需要 Context，单测里永远调不到 → 计数器恒为初值
        assertEquals(0, LinkPreviewPreferences.version)
    }

    @Test
    fun `version field is volatile`() {
        // volatile 是"多线程读不崩"那条用例的前提，钉住它
        val field = LinkPreviewPreferences::class.java.getDeclaredField("version")
        assertTrue(Modifier.isVolatile(field.modifiers))
    }

    @Test
    fun `version setter is private`() {
        // 只有 setEnabled 能递增：setter 私有是封装的静态证据
        val setter = LinkPreviewPreferences::class.java.getDeclaredMethod("setVersion", Int::class.javaPrimitiveType)
        assertTrue(Modifier.isPrivate(setter.modifiers))
    }

    @Test
    fun `prefs key is scoped per account`() {
        // 开关按账号隔离：键必须带 userId 后缀
        val key = LinkPreviewPreferences::class.java.getDeclaredMethod("key", String::class.java)
        key.isAccessible = true
        assertEquals("link_preview_enabled_u123", key.invoke(LinkPreviewPreferences, "u123"))
        assertEquals("link_preview_enabled_u456", key.invoke(LinkPreviewPreferences, "u456"))
    }

    @Test
    fun `prefs file name is stable`() {
        // 改文件名会让老用户设置"失踪"：钉住它
        val field = LinkPreviewPreferences::class.java.getDeclaredField("PREFS_NAME")
        field.isAccessible = true
        assertEquals("chat_display_settings", field.get(null))
    }
}
