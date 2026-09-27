package com.maodouchat.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.call.CallActionBus
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G337：通话前台服务的**真 Android 运行时**测试（总清单 Q03「前台服务仪器测试」第一片）。
 *
 * 为什么值得钉：
 * - 这是通话链路里唯一「进程被杀也在」的一层（WebRTC/信令保活的前提），它的状态机
 *   坏了不会崩，只会**静默断线**——典型的静默腐烂；
 * - `ACTION_HANG_UP` 是**不可变 PendingIntent** 的入口：callId 不匹配时必须忽略，
 *   否则通知栏上的挂断按钮会在下一次通话里误杀当前通话（跨通话串扰）；
 * - `activeCallId` 是 `IncomingCallObserver` 转发 hang-up 的依据，清空时机错了会把
 *   系统来电 UI 的挂断映射到错误的会话。
 *
 * 探针实测（本机 AVD）：仪器进程内 `startForegroundService` 可用（授予 RECORD_AUDIO +
 * POST_NOTIFICATIONS 后），通知以 `(tag=null, id=9001)` 出现——所以本测试**不需要宿主
 * Activity**，也不需要 fake：直接驱动真实 Service 生命周期，用真实 `NotificationManager`
 * 与 `CallActionBus` 观察结果。
 *
 * 纪律：权限只在测试前未授予时**授予、不回收**——实测回收 RECORD_AUDIO 会**杀死进程**
 * （撤销运行时权限 = 进程级撤销），一轮 connectedDebugAndroidTest 会因此整体崩掉。
 * 授予在测试会话内保持不变；`connectedDebugAndroidTest` 结束会卸载 APK，状态随之消失。
 * 服务在用例前后都确保已停止，避免把挂断请求/前台通知泄漏给同一次运行里的其它类。
 */
@RunWith(AndroidJUnit4::class)
class CallForegroundServiceInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val packageName: String get() = context.packageName
    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        grantIfMissing(Manifest.permission.RECORD_AUDIO)
        // 视频通话的 FGS type 是 MICROPHONE|CAMERA：缺 CAMERA 时 startForeground 抛
        // SecurityException，被服务自身的 catch 吞掉并 stopSelf()——表现为「第二次 start
        // 之后 activeCallId 变空」，本轮实测踩到过。
        grantIfMissing(Manifest.permission.CAMERA)
        grantIfMissing(Manifest.permission.POST_NOTIFICATIONS)
        stopServiceAndWait()
    }

    @After
    fun tearDown() {
        stopServiceAndWait()
    }

    @Test
    fun startingPublishesActiveCallIdAndOngoingCallNotification() {
        CallForegroundService.start(context, "Alice", isVideo = false, callId = "call-A")

        assertTrue("activeCallId 必须变为 call-A", awaitUntil { CallForegroundService.getActiveCallId() == "call-A" })
        val posted = awaitNotification(CallForegroundService.NOTIFICATION_ID)
        assertNotNull("通话前台服务必须挂出 9001 号通知", posted)
        assertEquals("通知必须是 CATEGORY_CALL", Notification.CATEGORY_CALL, posted!!.category)
        assertTrue(
            "通知必须 ongoing（不可滑掉）",
            posted.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )
        val channel = notificationManager.getNotificationChannel(CallForegroundService.CHANNEL_ID)
        assertNotNull("必须创建 call_active 渠道", channel)
        assertEquals(
            "通话进行中渠道必须是低打扰（IMPORTANCE_LOW）",
            NotificationManager.IMPORTANCE_LOW,
            channel!!.importance,
        )
    }

    @Test
    fun startingASecondCallReplacesActiveCallId() {
        CallForegroundService.start(context, "Alice", isVideo = false, callId = "call-A")
        assertTrue(awaitUntil { CallForegroundService.getActiveCallId() == "call-A" })

        CallForegroundService.start(context, "Bob", isVideo = true, callId = "call-B")
        assertTrue("第二通电话必须覆盖 activeCallId", awaitUntil { CallForegroundService.getActiveCallId() == "call-B" })
    }

    @Test
    fun hangUpWithMismatchedCallIdIsIgnored() {
        CallForegroundService.start(context, "Alice", isVideo = false, callId = "call-A")
        assertTrue(awaitUntil { CallForegroundService.getActiveCallId() == "call-A" })

        // 窗口内订阅 CallActionBus；错配的 callId 不得产生任何挂断请求。
        val noRequest = runBlocking {
            val deferred = async { withTimeoutOrNull(1_200) { CallActionBus.hangUpRequests.first() } }
            delay(100) // 确保订阅已建立
            sendHangUp("call-B")
            deferred.await() == null
        }

        assertTrue("错配 callId 的挂断必须被忽略（防跨通话串扰）", noRequest)
        assertEquals("服务不得被停掉", "call-A", CallForegroundService.getActiveCallId())
        assertNotNull(
            "通知不得被撤掉",
            awaitNotification(CallForegroundService.NOTIFICATION_ID),
        )
    }

    @Test
    fun hangUpWithMatchingCallIdRequestsHangUpAndStopsService() {
        CallForegroundService.start(context, "Alice", isVideo = false, callId = "call-A")
        assertTrue(awaitUntil { CallForegroundService.getActiveCallId() == "call-A" })

        val request = runBlocking {
            val deferred = async { withTimeoutOrNull(3_000) { CallActionBus.hangUpRequests.first() } }
            delay(100)
            sendHangUp("call-A")
            deferred.await()
        }

        assertNotNull("匹配 callId 必须发出挂断请求", request)
        assertEquals("call-A", request!!.callId)
        assertTrue("服务必须自行停止并把 activeCallId 清空", awaitUntil { CallForegroundService.getActiveCallId().isEmpty() })
        assertTrue(
            "服务停止后通知必须撤掉",
            awaitUntil {
                notificationManager.activeNotifications.none { it.id == CallForegroundService.NOTIFICATION_ID }
            },
        )
    }

    @Test
    fun stopWithoutHangUpClearsStateButDoesNotRequestHangUp() {
        CallForegroundService.start(context, "Alice", isVideo = false, callId = "call-A")
        assertTrue(awaitUntil { CallForegroundService.getActiveCallId() == "call-A" })

        val noRequest = runBlocking {
            val deferred = async { withTimeoutOrNull(1_200) { CallActionBus.hangUpRequests.first() } }
            delay(100)
            CallForegroundService.stop(context)
            deferred.await() == null
        }

        assertTrue("stop() 是系统/超时收尾，不得冒充用户挂断", noRequest)
        assertTrue("stop() 后 activeCallId 必须清空", awaitUntil { CallForegroundService.getActiveCallId().isEmpty() })
    }

    // ---- helpers ----------------------------------------------------------------

    private fun sendHangUp(callId: String) {
        context.startService(
            Intent(context, CallForegroundService::class.java).apply {
                action = CallForegroundService.ACTION_HANG_UP
                putExtra(CallForegroundService.EXTRA_CALL_ID, callId)
            },
        )
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    /**
     * 按 id 找活动通知。**不匹配 tag**：实测 `startForeground(id, notification, type)`
     * 挂出的通知 tag 为 null（`NOTIFICATION_TAG` 只用于旧路径的 cancel）。
     */
    private fun awaitNotification(id: Int): Notification? {
        var found: Notification? = null
        awaitUntil {
            found = notificationManager.activeNotifications
                .firstOrNull { it.id == id }
                ?.notification
            found != null
        }
        return found
    }

    private fun stopServiceAndWait() {
        CallForegroundService.stop(context)
        awaitUntil { CallForegroundService.getActiveCallId().isEmpty() }
    }

    private fun grantIfMissing(permission: String) {
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return
        val granted = runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
        }.isSuccess
        assertTrue("测试前置：授予 $permission 失败", granted)
    }
}
