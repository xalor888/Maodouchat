package com.maodouchat.session

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.MaodouchatApp
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U02 延伸：`AppRuntime` 的门面测试（真机）。
 *
 * 这三条都是「薄门面」也值得钉的理由：它们把 `CallViewModel` 里的
 * `MaodouchatApp.currentSessionGeneration()` / `instance.applicationScope` /
 * `as? MaodouchatApp` 三处直连换成了一处访问点——门面若在某个分支返回错东西
 * （世代读成常量、scope 读成非活跃、dispatcher 空判反了），表现会是**静默丢弃/丢失事件**，
 * 不会崩。用真机断言三者与 app 实态一致。
 *
 * `realtimeDispatcherOrNull` 的「非本应用实例 → null」原语义单独钉一条：
 * 传一个裸 `Application`（非 MaodouchatApp）必须返回 null 而不是抛。
 */
@RunWith(AndroidJUnit4::class)
class AppRuntimeInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun generationMatchesTheAppCounter() {
        assertEquals(MaodouchatApp.currentSessionGeneration(), AppRuntime.currentSessionGeneration)
    }

    /**
     * 常量映射防呆：世代必须是**实时读取**。本用例会 bump 进程级计数器（与登出/换号同一动作）——
     * 收集器都按每条事件携带的世代与实时值比较（各用例自清事件），因此对同次运行的其它类安全。
     */
    @Test
    fun generationFollowsTheAppCounterAcrossBump() {
        val before = AppRuntime.currentSessionGeneration
        MaodouchatApp.invalidateSessionGeneration()
        assertEquals("世代读取必须是实时映射，不能是常量", before + 1, AppRuntime.currentSessionGeneration)
    }

    @Test
    fun applicationScopeIsActive() {
        assertTrue(AppRuntime.applicationScope.isActive)
    }

    @Test
    fun realtimeDispatcherIsAvailableForTheRealApplication() {
        assertNotNull(AppRuntime.realtimeDispatcherOrNull(context.applicationContext as Application))
    }

    @Test
    fun nonMaodouchatApplicationYieldsNullDispatcher() {
        // 匿名 Application：与调用点「非本应用实例允许为空并跳过订阅」的语义对齐。
        val plain = object : Application() {}
        assertEquals(null, AppRuntime.realtimeDispatcherOrNull(plain))
    }
}
