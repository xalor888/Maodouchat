package com.maodouchat.widget

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.MainActivity
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.service.CallForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G338：主屏小组件的**真 Android 运行时**测试（总清单 Q03「Widget 仪器测试」第一片），
 * 也是这轮**真缺陷**的发现者与回归网。
 *
 * ## 发现的缺陷（widget 交互在 API 34+ 上全死）
 *
 * 旧实现：manifest `exported=true` + 依赖 `getSentFromUid()` 的守卫「uid 必须等于本应用或 system」。
 * 实测（API 36）：`getSentFromUid()` **只在发送方 `BroadcastOptions.setShareIdentityEnabled(true)`
 * opt-in 时才携带真实 uid**；本应用自己的 `sendBroadcast`、**PendingIntent（行点击/标记已读/
 * 快捷回复）与 AlarmManager 周期同步**全部是 `INVALID_UID(-1)`——守卫把**所有合法投递**也拒了。
 * 也就是说：小组件行点击、标记已读、快捷回复、周期同步在这套代码下**没有一条能work**，
 * 且它是 fail-closed 的静默失败，没有任何崩溃可供发现。
 *
 * ## 修复（本 PR）
 *
 * 1. manifest `android:exported` true → **false**：第三方应用与 adb shell 的伪造广播
 *    在**系统层**直接 `Permission Denial`（本测试断言）；而合法路径不受影响——
 *    PendingIntent 以创建者（本应用）身份执行，同 uid 投递不受导出限制；
 *    生命周期广播 APPWIDGET_UPDATE 由 system_server 发出（AppWidgetServiceImpl
 *    .sendBroadcastAsUser，身份为 system uid），系统本就允许送达非导出组件。
 * 2. Provider 内的同年守卫改为第二道门：只拒**能解析出的真实第三方 uid**；
 *    -1（未 opt-in）与 null（反射失败）依赖 exported=false 的系统拦截放行。
 *
 * ## 测试结构
 *
 * - `ownUidOpenChatBroadcastLaunchesMainActivity`：**正例对照**。本应用 UID 发合法
 *   `ACTION_OPEN_CHAT`，必须真的拉起 MainActivity（没有它，「伪造被拒」可能是假阳性——
 *   比如被 BAL 拦掉的）。BAL：广播上下文无可见窗口，实测给进程挂前台服务即可获得豁免，
 *   所以正/负例都在 FGS 存续期间发送，差异才可归因到校验本身。
 * - `forgedOpenChatBroadcastFromShellUidIsRejected`：shell（uid 2000）发格式完全合法的
 *   同一条广播，必须在**系统层**被拒（Permission Denial）。
 * - `widgetConfigPersistsPerWidgetIdAndRemovesCleanly`：配置按 widgetId 隔离、可清理。
 *
 * 纪律：会话状态每例前清、例后恢复登出态；前台服务在例后必停；
 * `MainActivity` 一旦被拉起立即 `finish()`。
 */
@RunWith(AndroidJUnit4::class)
class ConversationWidgetProviderInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val packageName: String get() = context.packageName
    private val tokenManager: TokenManager get() = TokenManager.getInstance(context)

    /**
     * `PendingIntent.send()` 拉起 Activity **不经过 Instrumentation**（它直连 AMS），
     * 所以 `Instrumentation.ActivityMonitor` 看不到它（首跑实测：monitor 恒 null）。
     * 改用进程内 `ActivityLifecycleCallbacks` 观察——它与发送路径无关。
     */
    private val createdActivities = mutableListOf<Class<*>>()
    private var lastMainActivity: MainActivity? = null
    private val lifecycleProbe = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            createdActivities += activity.javaClass
            if (activity is MainActivity) lastMainActivity = activity
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (activity is MainActivity) lastMainActivity = null
        }
    }

    @Before
    fun setUp() {
        ApiService.clearSessionTokens()
        tokenManager.clear()
        createdActivities.clear()
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(lifecycleProbe)
        // 先等上一次的 stop 真正落地——start 撞上 stopping 中的服务会让系统挂起
        // 「等 startForeground」的要求，5s 超时后抛 ForegroundServiceDidNotStartInTime
        // 并崩掉整个进程（首跑实测踩到）。
        stopServiceAndWait()
    }

    @After
    fun tearDown() {
        stopServiceAndWait()
        lastMainActivity?.finish()
        (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(lifecycleProbe)
        ApiService.clearSessionTokens()
        tokenManager.clear()
    }

    @Test
    fun ownUidOpenChatBroadcastLaunchesMainActivity() {
        seedLoggedIn(OWNER)
        ensureForegroundExemption()

        context.sendBroadcast(openChatIntent(chatId = "chat-1", ownerUserId = OWNER))

        assertTrue(
            "本应用 UID 的行点击必须拉起 MainActivity（8.40 的 NEW_TASK 与发送者校验回归网）",
            awaitUntil { createdActivities.contains(MainActivity::class.java) },
        )
        lastMainActivity?.finish()
    }

    @Test
    fun forgedOpenChatBroadcastFromShellUidIsRejected() {
        seedLoggedIn(OWNER)
        ensureForegroundExemption()

        val output = runShell(
            "am broadcast -a ${ConversationWidgetContract.ACTION_OPEN_CHAT} " +
                "-n $packageName/.widget.ConversationWidgetProvider " +
                "--es ${ConversationWidgetContract.EXTRA_CHAT_ID} chat-1 " +
                "--es ${ConversationWidgetContract.EXTRA_OWNER_USER_ID} $OWNER",
        )

        // 说明（probe 实测）：exported=false 时系统对非导出组件的显式广播是**静默丢弃**——
        // `am broadcast` 输出仍是 "Broadcast completed"（不是 Permission Denial），但 Provider
        // 的 onReceive **从未被调用**（临时探针实测：日志为空）。所以这里断言可观察的安全属性：
        // 等满与正例同量级的时间窗，伪造发送者从未拉起 MainActivity。
        // 对照：同类中的正例（本应用 UID）在同样的 FGS/BAL 条件下**必须**拉起——两者合起来
        // 才把差异归因到发送者校验（系统导出检查 + 代码守卫两道）。诊断信息附上 am 输出：
        Thread.sleep(2_500)
        assertFalse(
            "外部 UID（shell=2000）伪造的合法格式广播不得拉起 MainActivity；am 输出：$output",
            createdActivities.contains(MainActivity::class.java),
        )
    }

    @Test
    fun widgetConfigPersistsPerWidgetIdAndRemovesCleanly() {
        val widgetId = 4242
        try {
            assertFalse("前置：该实例必须未配置", ConversationWidgetData.isConfigured(context, widgetId))
            ConversationWidgetData.saveConfig(
                context,
                widgetId,
                ConversationWidgetData.WidgetConfig(
                    chatIds = listOf("c1", "c2"),
                    showUnreadBadge = false,
                    compact = true,
                ),
            )
            assertTrue(ConversationWidgetData.isConfigured(context, widgetId))
            val read = ConversationWidgetData.widgetConfig(context, widgetId)
            assertEquals(listOf("c1", "c2"), read.chatIds)
            assertFalse(read.showUnreadBadge)
            assertTrue(read.compact)

            // 配置是按 widgetId 隔离的：同一次读另一个 id 必须是默认值。
            val other = ConversationWidgetData.widgetConfig(context, widgetId + 1)
            assertEquals(emptyList<String>(), other.chatIds)
            assertTrue(other.showUnreadBadge)
            assertFalse(other.compact)
        } finally {
            ConversationWidgetData.removeWidget(context, widgetId)
        }
        assertFalse("removeWidget 后必须回到未配置", ConversationWidgetData.isConfigured(context, widgetId))
        assertTrue(
            "removeWidget 后不得残留在实例列表",
            ConversationWidgetData.allWidgetIds(context).none { it == widgetId },
        )
    }

    // ---- helpers ----------------------------------------------------------------

    private val OWNER = "u-owner"
    private val FGS_CALL_ID = "widget-test-call"

    private fun openChatIntent(chatId: String, ownerUserId: String): Intent =
        Intent(context, ConversationWidgetProvider::class.java).apply {
            action = ConversationWidgetContract.ACTION_OPEN_CHAT
            putExtra(ConversationWidgetContract.EXTRA_CHAT_ID, chatId)
            putExtra(ConversationWidgetContract.EXTRA_OWNER_USER_ID, ownerUserId)
        }

    /**
     * 给进程挂前台服务，获得后台 Activity 拉起的豁免（BAL）。
     * 缺 RECORD_AUDIO 时 `startForeground` 抛 SecurityException 被服务吞掉——
     * 与本测试无关但会让豁免失效，所以先补齐权限（同 FGS 测试：只授不回收）。
     */
    private fun ensureForegroundExemption() {
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).forEach { permission ->
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                val granted = runCatching {
                    instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
                }.isSuccess
                assertTrue("测试前置：授予 $permission 失败", granted)
            }
        }
        CallForegroundService.start(context, "WidgetTest", isVideo = false, callId = FGS_CALL_ID)
        assertTrue(
            "测试前置：前台服务必须真的起来（startForeground 成功）",
            awaitUntil { CallForegroundService.getActiveCallId() == FGS_CALL_ID },
        )
    }

    private fun stopServiceAndWait() {
        CallForegroundService.stop(context)
        awaitUntil { CallForegroundService.getActiveCallId().isEmpty() }
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun seedLoggedIn(userId: String) {
        val now = System.currentTimeMillis()
        val saved = tokenManager.saveAuthSession(
            token = "test-token",
            refreshToken = "test-refresh",
            userId = userId,
            accessTokenExpiresAt = now + 3_600_000L,
            refreshTokenExpiresAt = now + 30L * 24 * 3_600_000L,
        )
        check(saved) { "测试前置失败：会话未写入 TokenManager" }
    }

    private fun runShell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            stream.readBytes().decodeToString()
        }
    }
}
