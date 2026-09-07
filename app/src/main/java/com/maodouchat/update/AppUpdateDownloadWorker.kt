package com.maodouchat.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * P09：下载并校验官服 APK；成功后弹出系统安装器。
 * 可重试错误（网络/HTTP）走 [Result.retry]；校验失败不重试。
 */
class AppUpdateDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val apkUrl = inputData.getString(AppUpdateDownloadScheduler.KEY_APK_URL)
            ?.takeIf(String::isNotBlank)
            ?: return fail("apk_url_missing")
        val sha256 = inputData.getString(AppUpdateDownloadScheduler.KEY_SHA256).orEmpty()
        val versionCode = inputData.getInt(AppUpdateDownloadScheduler.KEY_VERSION_CODE, 0)
        val outcome = OfficialApkInstaller.downloadAndVerify(
            context = applicationContext,
            apkUrl = apkUrl,
            expectedSha256 = sha256,
            expectedVersionCode = versionCode,
            onProgress = { percent ->
                setProgress(AppUpdateDownloadScheduler.progressData(percent))
            },
        )
        return outcome.fold(
            onSuccess = { file ->
                OfficialApkInstaller.promptInstall(applicationContext, file)
                Result.success()
            },
            onFailure = { error ->
                val code = error.message.orEmpty()
                if (AppUpdateDownloadRetryPolicy.isRetryable(code) && runAttemptCount < 3) {
                    Result.retry()
                } else {
                    fail(code.ifBlank { "download_failed" })
                }
            },
        )
    }

    private suspend fun fail(code: String): Result {
        setProgress(AppUpdateDownloadScheduler.failureData(code))
        return Result.failure(AppUpdateDownloadScheduler.failureData(code))
    }
}
