package com.maodouchat.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Official in-app APK install: HTTPS only, FileProvider, never GitHub/browser.
 */
object OfficialApkInstaller {

    private const val DIR = "updates"
    private const val FILE = "maodou-update.apk"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun canOfferHttps(apkUrl: String): Boolean =
        AppUpdatePolicy.isOfficialApkUrl(apkUrl)

    suspend fun downloadAndPromptInstall(
        context: Context,
        apkUrl: String,
        onProgress: (percent: Int) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val url = apkUrl.trim()
        if (!url.lowercase().startsWith("https://")) {
            return@withContext Result.failure(IllegalArgumentException("apk_not_https"))
        }
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, FILE)
        runCatching { if (target.exists()) target.delete() }
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext Result.failure(IllegalStateException("http_${response.code}"))
            }
            val finalUrl = response.request.url.toString()
            if (!finalUrl.lowercase().startsWith("https://")) {
                return@withContext Result.failure(IllegalStateException("redirect_not_https"))
            }
            val body = response.body ?: return@withContext Result.failure(IllegalStateException("empty_body"))
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0L) {
                            val percent = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                    output.flush()
                }
            }
        }
        if (!target.isFile || target.length() < 64L) {
            return@withContext Result.failure(IllegalStateException("apk_too_small"))
        }
        withContext(Dispatchers.Main) {
            promptInstall(context, target)
        }
        Result.success(Unit)
    }

    fun promptInstall(context: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
