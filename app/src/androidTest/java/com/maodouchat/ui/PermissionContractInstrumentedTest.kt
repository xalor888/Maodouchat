package com.maodouchat.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.notification.MessageNotificationService
import com.maodouchat.notification.NotificationSlotPolicy
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G341：权限「契约面」的真机测试（总清单 Q03「权限仪器测试」——五维里的最后一维）。
 *
 * 已有的 `StartupPermissionPolicyTest`（JVM 3 例）覆盖纯策略；本类补**真机契约**：
 *
 * 1. **清单声明契约**：功能依赖的运行时/FGS 权限必须真的在 Manifest 里。缺一个的表现不是
 *    崩溃，而是**特定 API 等级/特定机型上的功能静默失效**——本轮 G337 就实测踩过：
 *    视频通话的 FGS type 需要 CAMERA，缺它时 `startForeground` 抛 SecurityException
 *    被服务自身吞掉、表现为「第二次 start 后 activeCallId 变空」。把它变成一条断言。
 * 2. **策略与设备实态一致**：用真机的 `checkSelfPermission` 喂 `StartupPermissionPolicy`，
 *    两侧必须同结论（授予 → 不请求；未授予且 SDK≥33 → 请求 POST_NOTIFICATIONS）。
 * 3. **授予后能力确实成立**：授予 POST_NOTIFICATIONS + 播种会话后，`showTestNotification`
 *    必须真的出现在 `activeNotifications`（这是「权限 → 能力」的端到端，而非只看标志位）。
 *
 * 纪律：不回收运行时权限（撤销会杀死进程，G337 实测教训）；会话例后恢复登出态。
 */
@RunWith(AndroidJUnit4::class)
class PermissionContractInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val packageName: String get() = context.packageName
    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        ApiService.clearSessionTokens()
        TokenManager.getInstance(context).clear()
    }

    @After
    fun tearDown() {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NotificationSlotPolicy.TEST_TAG, 0)
        }
        ApiService.clearSessionTokens()
        TokenManager.getInstance(context).clear()
    }

    // ---- 1. 清单声明契约 ----------------------------------------------------------

    @Test
    fun manifestDeclaresRuntimePermissionsTheFeaturesDependOn() {
        val declared = declaredPermissions()
        listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
        ).forEach { permission ->
            assertTrue(
                "Manifest 必须声明 $permission（缺它时对应功能会在真机/新 API 上静默失效）",
                permission in declared,
            )
        }
    }

    @Test
    fun manifestDeclaresForegroundServicePermissionsForCalls() {
        val declared = declaredPermissions()
        listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.FOREGROUND_SERVICE_CAMERA",
        ).forEach { permission ->
            assertTrue(
                "Manifest 必须声明 $permission（FGS type 在 API 34+ 校验，缺它 startForeground 抛错）",
                permission in declared,
            )
        }
    }

    // ---- 2. 策略与设备实态一致 ----------------------------------------------------

    @Test
    fun policyAgreesWithRealDeviceGrantState() {
        val sdk = android.os.Build.VERSION.SDK_INT
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val needed = StartupPermissionPolicy.permissionsToRequest(
            sdkInt = sdk,
            isGranted = { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED },
        )
        if (sdk < StartupPermissionPolicy.TIRAMISU_SDK) {
            assertTrue("SDK<$sdk 不应请求任何权限，实际=$needed", needed.isEmpty())
        } else if (granted) {
            assertTrue("已授予时不应再请求，实际=$needed", needed.isEmpty())
        } else {
            assertEquals(
                "未授予时必须请求通知权限（真机实态=$granted）",
                listOf(StartupPermissionPolicy.POST_NOTIFICATIONS),
                needed,
            )
        }
    }

    // ---- 3. 授予后能力端到端 ------------------------------------------------------

    @Test
    fun grantedNotificationsActuallyPostThroughTheTestNotificationPath() {
        grantPostNotificationsIfNeeded()
        seedLoggedIn("u-perm")

        MessageNotificationService.showTestNotification(context)

        val posted = awaitNotification(NotificationSlotPolicy.TEST_TAG)
        assertTrue(
            "授予 POST_NOTIFICATIONS 后，测试通知必须真的挂出（权限 → 能力的端到端）",
            posted != null,
        )
        assertTrue(
            "系统也应当把通知视为已启用",
            NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
    }

    // ---- helpers ----------------------------------------------------------------

    private fun declaredPermissions(): Set<String> =
        context.packageManager
            .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()

    private fun grantPostNotificationsIfNeeded() {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val granted = runCatching {
            instrumentation.uiAutomation.grantRuntimePermission(
                packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }.isSuccess
        assertTrue("测试前置：授予 POST_NOTIFICATIONS 失败", granted)
    }

    private fun seedLoggedIn(userId: String) {
        val now = System.currentTimeMillis()
        val saved = TokenManager.getInstance(context).saveAuthSession(
            token = "test-token",
            refreshToken = "test-refresh",
            userId = userId,
            accessTokenExpiresAt = now + 3_600_000L,
            refreshTokenExpiresAt = now + 30L * 24 * 3_600_000L,
        )
        check(saved) { "测试前置失败：会话未写入 TokenManager" }
    }

    private fun awaitNotification(tag: String, timeoutMs: Long = 5_000): android.app.Notification? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = notificationManager.activeNotifications
                .firstOrNull { it.tag == tag }
                ?.notification
            if (found != null) return found
            Thread.sleep(50)
        }
        return null
    }
}
