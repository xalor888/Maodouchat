package com.maodouchat.notification

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.MainActivity
import com.maodouchat.navigation.NotificationTarget
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.service.CallForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G339：消息通知「**投递 → 点击**」整链的真机测试（总清单 Q03「通知」项的行为级补齐）。
 *
 * 此前的覆盖缺口：`NotificationIntentConsumerInstrumentedTest` 只覆盖「点击之后」的消费侧
 * （拿到 Intent extras 之后的行为）；本类覆盖**产侧**——通知由 `MessageNotificationService`
 * 真实挂出、`NotificationManager` 真实可见、点击用的 `contentIntent` 真能把 `MainActivity`
 * 拉起来。两片合起来才是检查单里写的「通知投递→点击整链」。
 *
 * 钉住的契约：
 * - 通知槽位：tag = `maodouchat_<chatId>`、id = 0（会话级独立槽位，防跨会话覆盖）；
 * - 点击意图携带 `EXTRA_OPEN_CHAT_ID` 与 `EXTRA_NOTIFICATION_OWNER_USER_ID`（消费侧据此导航）；
 * - 渠道：单聊 `messages_v4` / 群聊 `group_messages_v4`（0.72 群独立渠道）；
 * - owner 不匹配（换号/共享设备）不得挂出；`cancelMessage` 必须清掉槽位；
 * - `contentIntent.send()` 真的拉起 MainActivity（BAL：与 Widget 测试同法用前台服务豁免）。
 *
 * 纪律（同前几片）：TokenManager 真实 SharedPreferences——每例前清、例后恢复登出态并撤销
 * 本类挂出的通知；前台服务在例后必停；`MainActivity` 一旦拉起立即 `finish()`。
 */
@RunWith(AndroidJUnit4::class)
class MessageNotificationServiceInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val packageName: String get() = context.packageName
    private val tokenManager: TokenManager get() = TokenManager.getInstance(context)
    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private companion object {
        const val OWNER = "u-owner"
        const val CHAT_ID = "chat-A"
        const val GROUP_CHAT_ID = "group-A"
        const val FGS_CALL_ID = "notif-test-call"
        const val NOTIFY_ID = 0 // NotificationSlotPolicy.messageNotifyId()
    }

    private val createdActivities = mutableListOf<Class<*>>()
    private var lastMainActivity: MainActivity? = null

    /**
     * 读 MainActivity 的 `notificationTarget`（消费侧落点）。
     *
     * 时机的坑（实测）：`Application.ActivityLifecycleCallbacks.onActivityCreated` 是从
     * **`Activity.onCreate` 基类实现里**（`super.onCreate` 内）回调的，比 MainActivity 自己的
     * `consumeNotificationIntent(intent)` 还早——在回调里读必然读到 null。而且消费成功后
     * `NotificationIntents.clearFrom(intent)` 会清掉 extras，也不能读 activity.intent。
     * 所以这里从测试线程**轮询**读私有字段：目标在 `filterNotNull().collect` 的等待/导航期间
     * 会保留至少一拍（ContinueWaiting 250ms/轮），轮询能稳定命中。
     */
    private fun readNotificationTarget(activity: MainActivity): NotificationTarget? = try {
        val field = MainActivity::class.java.getDeclaredField("notificationTarget")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(activity) as MutableStateFlow<NotificationTarget?>).value
    } catch (t: Throwable) {
        null
    }

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

    /** 本类挂出过通知的 tag（例后统一清理）。 */
    private val usedTags = mutableListOf<String>()

    @Before
    fun setUp() {
        ApiService.clearSessionTokens()
        tokenManager.clear()
        createdActivities.clear()
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(lifecycleProbe)
        grantIfMissing(Manifest.permission.POST_NOTIFICATIONS)
        // 前台服务（BAL 豁免用）需要 RECORD_AUDIO 才能 startForeground(MICROPHONE)：
        // 缺它时服务抛 SecurityException 被自身吞掉并 stopSelf()——同 FGS/Widget 两片的实测结论。
        grantIfMissing(Manifest.permission.RECORD_AUDIO)
        stopServiceAndWait()
    }

    @After
    fun tearDown() {
        usedTags.forEach { tag -> runCatching { NotificationManagerCompat.from(context).cancel(tag, NOTIFY_ID) } }
        usedTags.clear()
        stopServiceAndWait()
        lastMainActivity?.finish()
        (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(lifecycleProbe)
        ApiService.clearSessionTokens()
        tokenManager.clear()
    }

    @Test
    fun postsTappableNotificationWithOwnerAndChatIdAndTapLaunchesMainActivity() {
        seedLoggedIn(OWNER)
        ensureForegroundExemption()

        showMessage(chatId = CHAT_ID, senderName = "Alice", preview = "hello world")

        val posted = awaitMessageNotification(CHAT_ID)
        assertNotNull("消息通知必须挂出（槽位 tag=maodouchat_<chatId>, id=0）", posted)
        assertEquals("messages_v4", posted!!.channelId)
        assertEquals("Alice", posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("hello world", posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        posted.contentIntent.send()
        assertTrue(
            "点击通知必须真的拉起 MainActivity（投递→点击整链）",
            awaitUntil { createdActivities.contains(MainActivity::class.java) },
        )
        // 整链终点：点击意图携带的会话 id + 账号归属，经消费侧校验后落到 NotificationTarget.Chat。
        var observed: NotificationTarget? = null
        assertTrue(
            "点击后消费侧必须把目标落成 Chat($CHAT_ID)（实测最后一次读到：$observed）",
            awaitUntil(5_000) {
                val activity = lastMainActivity ?: return@awaitUntil false
                observed = readNotificationTarget(activity)
                (observed as? NotificationTarget.Chat)?.id == CHAT_ID
            },
        )
        assertEquals("落点必须带账号归属", OWNER, (observed as NotificationTarget.Chat).ownerUserId)
        lastMainActivity?.finish()
    }

    @Test
    fun groupMessagesUseDedicatedChannel() {
        seedLoggedIn(OWNER)

        showMessage(chatId = GROUP_CHAT_ID, senderName = "Group Bob", preview = "hi all", isGroup = true)

        val posted = awaitMessageNotification(GROUP_CHAT_ID)
        assertNotNull(posted)
        assertEquals("群聊必须走独立渠道 group_messages_v4", "group_messages_v4", posted!!.channelId)
    }

    @Test
    fun notificationForAnotherOwnerIsNotPosted() {
        seedLoggedIn(OWNER)
        // 同一条用例内先做正对照：owner 匹配时必须能挂出，否则「没挂出」可能是环境问题。
        showMessage(chatId = CHAT_ID, senderName = "Alice", preview = "control")
        assertNotNull("正对照：匹配 owner 的通知必须挂出", awaitMessageNotification(CHAT_ID))
        NotificationManagerCompat.from(context).cancel("maodouchat_$CHAT_ID", NOTIFY_ID)

        showMessage(chatId = CHAT_ID, senderName = "Alice", preview = "intruder", expectedUserId = "u-intruder")

        Thread.sleep(1_000)
        assertNull("owner 不匹配（换号/共享设备）不得挂出", findMessageNotification(CHAT_ID))
    }

    @Test
    fun cancelMessageClearsTheSlot() {
        seedLoggedIn(OWNER)

        showMessage(chatId = CHAT_ID, senderName = "Alice", preview = "to be cancelled")
        assertNotNull(awaitMessageNotification(CHAT_ID))

        MessageNotificationService.cancelMessage(context, CHAT_ID)

        assertTrue(
            "cancelMessage 后槽位必须清空",
            awaitUntil { findMessageNotification(CHAT_ID) == null },
        )
    }

    // ---- helpers ----------------------------------------------------------------

    private fun showMessage(
        chatId: String,
        senderName: String,
        preview: String,
        expectedUserId: String = OWNER,
        isGroup: Boolean = false,
    ) {
        usedTags += "maodouchat_$chatId"
        MessageNotificationService.showMessage(
            context = context,
            chatId = chatId,
            senderName = senderName,
            preview = preview,
            messageId = "m-1",
            expectedUserId = expectedUserId,
            isGroup = isGroup,
        )
    }

    private fun findMessageNotification(chatId: String): Notification? =
        notificationManager.activeNotifications
            .firstOrNull { it.tag == "maodouchat_$chatId" && it.id == NOTIFY_ID }
            ?.notification

    private fun awaitMessageNotification(chatId: String, timeoutMs: Long = 5_000): Notification? {
        var found: Notification? = null
        awaitUntil(timeoutMs) {
            found = findMessageNotification(chatId)
            found != null
        }
        return found
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

    /** 给进程挂前台服务以获得后台拉起 Activity 的豁免（BAL），同 Widget 测试的实测结论。 */
    private fun ensureForegroundExemption() {
        CallForegroundService.start(context, "NotifTest", isVideo = false, callId = FGS_CALL_ID)
        assertTrue(
            "测试前置：前台服务必须真的起来",
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

    private fun grantIfMissing(permission: String) {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        val granted = runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
        }.isSuccess
        assertTrue("测试前置：授予 $permission 失败", granted)
    }
}
