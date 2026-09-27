package com.maodouchat.update

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
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
 * G340：`AppUpdateDownloadWorker` 的真机测试（总清单 Q03“更新器”项的行为级补齐）。
 *
 * 此前的缺口（见 `AppUpdateDownloadSchedulerTest` 自己的注释）：`enqueue`/`cancel`/`observe`
 * 「要真跑 WorkManager」、真正干下载活的 Worker 未测。本类分两组补齐：
 *
 * **A 组：Worker 的拒绝/重试门（`TestListenableWorkerBuilder` 直驱，不起调度器）**
 * `OfficialApkInstaller.downloadAndVerify` 的门序是「官方 HTTPS → sha256 格式 → versionCode>0
 * → 网络」，前三个门都在联网**之前**返回，所以它们既确定又零网络依赖：
 * - 缺 URL → `apk_url_missing`；
 * - 非官方 URL（http/陌生 host）→ `apk_not_official`（不可重试 → `Result.failure`）；
 * - sha256 缺失/非法 → `apk_sha256_missing_or_invalid`；
 * - versionCode 缺失 → `apk_version_code_missing`。
 * 再加一条**可重试分类**：官方域名的不可达端口（连接失败类）必须 `Result.retry()`
 * 而不是 `failure`（重试分类错了 = 更新静默放弃或无限重试）。
 *
 * **B 组：真 WorkManager 的 enqueue/observe/cancel（唯一任务语义）**
 * 用**真实** WorkManager（app 由 androidx.startup 自动初始化，不走 `work-testing` 的
 * 测试替身——那会要求进程内尚未初始化）：
 * - enqueue 一个必失败（非官方 URL）的任务 → `observe` 流等到 FAILED，
 *   且 `errorOf` 读出 `apk_not_official`——证明「调度器 → Worker → failureData → observe」整条真链路；
 * - enqueue 一个会 retry 的任务（官方域名不可达端口，backoff 15s）→ 等到 ENQUEUED/RUNNING →
 *   `cancel` → 必须收敛到 CANCELLED（取消不得留下悬挂任务）。
 *
 * **未覆盖（诚实登记）**：成功路径需要真实官方 HTTPS 源 + 与当前签名一致的 APK，无法在本地/CI
 * 伪造；`promptInstall` 的系统安装器交互同样未覆盖。
 *
 * 纪律：每例前后都用生产 `cancel` 清掉唯一任务，避免把失败任务留给同一次运行里的其它类
 * （`AppUpdateDownloadSchedulerTest` 的纯函数例不受影响，但保持干净）。
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateDownloadWorkerInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        AppUpdateDownloadScheduler.cancel(context)
    }

    @After
    fun tearDown() {
        AppUpdateDownloadScheduler.cancel(context)
    }

    // ---- A 组：Worker 拒绝/重试门 -------------------------------------------------

    @Test
    fun missingUrlFailsWithCode() {
        val result = runWorker(input = workDataOf())
        assertTrue("缺 URL 必须 failure（不可重试）", result is androidx.work.ListenableWorker.Result.Failure)
        assertEquals("apk_url_missing", errorOf(result))
    }

    @Test
    fun nonOfficialUrlFailsWithoutRetry() {
        val result = runWorker(
            input = workDataOf(
                AppUpdateDownloadScheduler.KEY_APK_URL to "http://example.com/update.apk",
                AppUpdateDownloadScheduler.KEY_SHA256 to VALID_SHA,
                AppUpdateDownloadScheduler.KEY_VERSION_CODE to 1085,
            ),
        )
        assertTrue("非官方 URL 必须 failure（不可重试，重试也只会再被拒）", result is androidx.work.ListenableWorker.Result.Failure)
        assertEquals("apk_not_official", errorOf(result))
    }

    @Test
    fun invalidSha256FailsBeforeNetwork() {
        val result = runWorker(
            input = workDataOf(
                AppUpdateDownloadScheduler.KEY_APK_URL to OFFICIAL_URL,
                AppUpdateDownloadScheduler.KEY_SHA256 to "not-a-sha",
                AppUpdateDownloadScheduler.KEY_VERSION_CODE to 1085,
            ),
        )
        assertTrue(result is androidx.work.ListenableWorker.Result.Failure)
        assertEquals("apk_sha256_missing_or_invalid", errorOf(result))
    }

    @Test
    fun missingVersionCodeFailsBeforeNetwork() {
        val result = runWorker(
            input = workDataOf(
                AppUpdateDownloadScheduler.KEY_APK_URL to OFFICIAL_URL,
                AppUpdateDownloadScheduler.KEY_SHA256 to VALID_SHA,
                // KEY_VERSION_CODE 缺席 → 默认 0
            ),
        )
        assertTrue(result is androidx.work.ListenableWorker.Result.Failure)
        assertEquals("apk_version_code_missing", errorOf(result))
    }

    @Test
    fun unreachableOfficialHostIsClassifiedRetryable() {
        val result = runWorker(
            input = workDataOf(
                // 官方域名 + 必然拒绝连接的端口：连接失败属于可重试类，必须 Result.retry()。
                AppUpdateDownloadScheduler.KEY_APK_URL to "https://chat.mdou.me:1/update.apk",
                AppUpdateDownloadScheduler.KEY_SHA256 to VALID_SHA,
                AppUpdateDownloadScheduler.KEY_VERSION_CODE to 1085,
            ),
        )
        assertTrue(
            "连接失败必须分类为可重试（Result.retry），实际：$result",
            result is androidx.work.ListenableWorker.Result.Retry,
        )
    }

    // ---- B 组：真 WorkManager 的 enqueue/observe/cancel ---------------------------

    @Test
    fun realWorkManagerEnqueueRunsWorkerAndObservesFailure() {
        AppUpdateDownloadScheduler.enqueue(
            context = context,
            apkUrl = "http://example.com/update.apk", // 必失败门：非官方 URL，零网络依赖
            expectedSha256 = VALID_SHA,
            expectedVersionCode = 1085,
        )

        val terminal = awaitTerminalWorkInfo(timeoutMs = 20_000)
        assertNotNull("任务必须能收敛到终止态（真 WorkManager 真的把 Worker 跑起来了）", terminal)
        assertEquals(WorkInfo.State.FAILED, terminal!!.state)
        assertEquals("apk_not_official", AppUpdateDownloadScheduler.errorOf(terminal))
    }

    @Test
    fun cancelConvergesPendingUniqueWorkToCancelled() {
        AppUpdateDownloadScheduler.enqueue(
            context = context,
            // 会 retry（backoff 15s）的任务：cancel 前它停在 ENQUEUED/RUNNING，
            // 让取消有确定的观察窗口。
            apkUrl = "https://chat.mdou.me:1/update.apk",
            expectedSha256 = VALID_SHA,
            expectedVersionCode = 1085,
        )
        assertTrue(
            "任务必须先进入在途状态",
            awaitCondition(15_000) {
                val state = runBlocking {
                    withTimeoutOrNull(2_000) { AppUpdateDownloadScheduler.observe(context).first() }
                }?.state
                state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
            },
        )

        AppUpdateDownloadScheduler.cancel(context)

        assertTrue(
            "cancel 后必须收敛到 CANCELLED",
            awaitCondition(15_000) {
                runBlocking {
                    withTimeoutOrNull(2_000) { AppUpdateDownloadScheduler.observe(context).first() }
                }?.state == WorkInfo.State.CANCELLED
            },
        )
    }

    // ---- helpers ----------------------------------------------------------------

    private companion object {
        const val VALID_SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val OFFICIAL_URL = "https://chat.mdou.me/update.apk"
    }

    private fun runWorker(input: androidx.work.Data): androidx.work.ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<AppUpdateDownloadWorker>(context)
            .setInputData(input)
            .build()
        return runBlocking { worker.doWork() }
    }

    private fun errorOf(result: androidx.work.ListenableWorker.Result): String? {
        val data = when (result) {
            is androidx.work.ListenableWorker.Result.Failure -> result.outputData
            is androidx.work.ListenableWorker.Result.Retry -> result.outputData
            else -> return null
        }
        return data.getString(AppUpdateDownloadScheduler.KEY_ERROR)
    }

    private fun awaitTerminalWorkInfo(timeoutMs: Long): WorkInfo? = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            AppUpdateDownloadScheduler.observe(context).first { info ->
                info != null && info.state.isFinished
            }
        }
    }

    private fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
