package com.maodouchat.ui.screen.groupplay

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.maodouchat.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * G173：`GroupPlayViewModelSupport` 两个扩展的测试。
 *
 * 它们是从三个群玩 ViewModel 的私有副本收敛来的——收敛前没人测过，
 * 因为它们是私有成员。现在成了共享实现，必须有测试兜底，
 * 否则一次「顺手简化」就会同时影响三个页面。
 */
class GroupPlayViewModelSupportTest {

    private lateinit var vm: AndroidViewModel

    @Before
    fun setUp() {
        vm = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
    }

    // 说明：`authToken()` 本机**无法**单测——它内部走 TokenManager.getInstance，
    // 构造函数读 SharedPreferences 并打 android.util.Log，而本机没有 Robolectric
    // （见 app/build.gradle.kts 里被注释掉的依赖）。收敛后它仍是共享实现，
    // 这条限制记在台账里，不假装覆盖过。

    @Test
    fun `localizedString resolves through the application resources`() {
        val app = mockk<Application> {
            every { getString(R.string.app_name) } returns "Maodou"
        }
        every { vm.getApplication<Application>() } returns app
        assertEquals("Maodou", vm.localizedString(R.string.app_name))
    }

    @Test
    fun `groupPlayChatId reads chatId from the saved state handle`() {
        assertEquals("c1", groupPlayChatId(mockk { every { get<String>("chatId") } returns "c1" }))
    }

    @Test
    fun `groupPlayChatId is empty when chatId is missing or not a string`() {
        assertEquals("", groupPlayChatId(mockk { every { get<String>("chatId") } returns null }))
    }
}
