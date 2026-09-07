package com.maodouchat.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * P09：官服 APK 下载进入 WorkManager（可恢复、联网约束、唯一任务）。
 * Worker 内完成下载+SHA/包名/签名/versionCode 校验，成功后弹出安装器。
 */
object AppUpdateDownloadScheduler {
    const val UNIQUE_WORK = "app_update_download"
    const val KEY_APK_URL = "apk_url"
    const val KEY_SHA256 = "apk_sha256"
    const val KEY_VERSION_CODE = "version_code"
    const val KEY_PROGRESS = "progress"
    const val KEY_ERROR = "error"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueue(
        context: Context,
        apkUrl: String,
        expectedSha256: String,
        expectedVersionCode: Int,
    ) {
        val request = OneTimeWorkRequestBuilder<AppUpdateDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_APK_URL, apkUrl)
                    .putString(KEY_SHA256, expectedSha256)
                    .putInt(KEY_VERSION_CODE, expectedVersionCode)
                    .build()
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
    }

    fun observe(context: Context): Flow<WorkInfo?> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK)
            .map { infos -> infos.firstOrNull() }

    fun progressOf(info: WorkInfo?): Int =
        info?.progress?.getInt(KEY_PROGRESS, 0) ?: 0

    fun errorOf(info: WorkInfo?): String? =
        info?.outputData?.getString(KEY_ERROR)
            ?: info?.progress?.getString(KEY_ERROR)

    fun progressData(percent: Int) = workDataOf(KEY_PROGRESS to percent.coerceIn(0, 100))

    fun failureData(error: String) = workDataOf(KEY_ERROR to error.take(200))
}
