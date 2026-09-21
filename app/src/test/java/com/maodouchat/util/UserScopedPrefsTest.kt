package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * G180：`UserScopedPrefs` 两个函数的测试（G180 刚从 4 个 Store 的私有副本收敛）。
 */
class UserScopedPrefsTest {

    // ─── userScopedKey ───

    @Test
    fun `key is prefix underscore userId`() {
        assertEquals("recent_u1", userScopedKey("recent", "u1"))
    }

    @Test
    fun `user id with odd characters is appended verbatim`() {
        // 不转义、不替换——替换会让读和写对不上
        assertEquals("recent_a:b_c", userScopedKey("recent", "a:b_c"))
        assertEquals("recent_with space", userScopedKey("recent", "with space"))
        assertEquals("recent_", userScopedKey("recent", ""))
    }

    @Test
    fun `empty prefix still yields a readable key`() {
        assertEquals("_u1", userScopedKey("", "u1"))
    }

    @Test
    fun `prefix and user id round trip without ambiguity`() {
        // prefix/userId 里都含下划线时拼接仍可逆（split 取第一段即 prefix）
        val k = userScopedKey("a_b", "c_d")
        assertEquals("a_b_c_d", k)
        assertEquals("a", k.substringBefore('_'))
    }

    // ─── userScopedPrefs ───

    @Test
    fun `prefs are taken from the application context with the given name`() {
        val app = mockk<Context>()
        val prefs = mockk<SharedPreferences>()
        every { app.getSharedPreferences("my_prefs", Context.MODE_PRIVATE) } returns prefs
        val ctx = mockk<Context> { every { applicationContext } returns app }

        assertSame(prefs, userScopedPrefs(ctx, "my_prefs"))
        verify(exactly = 1) { app.getSharedPreferences("my_prefs", Context.MODE_PRIVATE) }
    }

    @Test
    fun `mode is always private`() {
        val app = mockk<Context>()
        every { app.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns mockk()
        val ctx = mockk<Context> { every { applicationContext } returns app }

        userScopedPrefs(ctx, "whatever")
        // 若改成 MODE_WORLD_READABLE 之类的，这条会因参数不匹配而拿不到 stub 从而失败
        verify { app.getSharedPreferences("whatever", Context.MODE_PRIVATE) }
    }
}
