package com.maodouchat.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Official in-app APK install: HTTPS only, FileProvider, never GitHub/browser.
 */
object OfficialApkInstaller {

    private const val DIR = "updates"
    private const val FILE = "maodou-update.apk"

    private val client = com.maodouchat.network.HttpClients.largeDownload()

    fun canOfferHttps(apkUrl: String): Boolean =
        AppUpdatePolicy.isOfficialApkUrl(apkUrl)

    /**
     * Download + integrity gates only. Caller (UI or Worker) decides when to [promptInstall].
     */
    suspend fun downloadAndVerify(
        context: Context,
        apkUrl: String,
        expectedSha256: String = "",
        expectedVersionCode: Int = 0,
        onProgress: suspend (percent: Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val url = apkUrl.trim()
        if (!AppUpdatePolicy.isOfficialApkUrl(url)) {
            return@withContext Result.failure(IllegalArgumentException("apk_not_official"))
        }
        if (!AppUpdatePolicy.hasExpectedApkSha256(expectedSha256)) {
            return@withContext Result.failure(IllegalArgumentException("apk_sha256_missing_or_invalid"))
        }
        if (expectedVersionCode <= 0) {
            return@withContext Result.failure(IllegalArgumentException("apk_version_code_missing"))
        }
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, FILE)
        runCatching { if (target.exists()) target.delete() }
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("http_${response.code}"))
                }
                val finalUrl = response.request.url.toString()
                if (!AppUpdatePolicy.isOfficialApkUrl(finalUrl)) {
                    return@withContext Result.failure(IllegalStateException("redirect_host_not_official"))
                }
                val body = response.body
                    ?: return@withContext Result.failure(IllegalStateException("empty_body"))
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
                                onProgress(percent)
                            }
                        }
                        output.flush()
                    }
                }
            }
        } catch (error: Exception) {
            target.delete()
            return@withContext Result.failure(error)
        }
        if (!target.isFile || target.length() < 64L) {
            target.delete()
            return@withContext Result.failure(IllegalStateException("apk_too_small"))
        }
        val actualSha256 = sha256(target)
        if (!AppUpdatePolicy.matchesExpectedApkSha256(actualSha256, expectedSha256)) {
            target.delete()
            return@withContext Result.failure(IllegalStateException("apk_sha256_mismatch"))
        }
        if (!isPackageSignerAndVersionTrusted(context, target, expectedVersionCode)) {
            target.delete()
            return@withContext Result.failure(IllegalStateException("apk_package_signer_or_version_mismatch"))
        }
        Result.success(target)
    }

    suspend fun downloadAndPromptInstall(
        context: Context,
        apkUrl: String,
        expectedSha256: String = "",
        expectedVersionCode: Int = 0,
        onProgress: suspend (percent: Int) -> Unit = {},
    ): Result<Unit> {
        val file = downloadAndVerify(
            context = context,
            apkUrl = apkUrl,
            expectedSha256 = expectedSha256,
            expectedVersionCode = expectedVersionCode,
            onProgress = onProgress,
        ).getOrElse { return Result.failure(it) }
        withContext(Dispatchers.Main) {
            promptInstall(context, file)
        }
        return Result.success(Unit)
    }

    private fun isPackageSignerAndVersionTrusted(
        context: Context,
        apk: File,
        expectedVersionCode: Int,
    ): Boolean = runCatching {
        val packageManager = context.packageManager
        val archiveInfo = packageManager.getPackageArchiveInfo(apk.absolutePath, signingInfoFlags())
            ?: return false
        val installedInfo = packageManager.getPackageInfo(context.packageName, signingInfoFlags())
        if (!AppUpdateInstallPolicy.isPackageAndSignerTrusted(
                installedPackageName = context.packageName,
                archivePackageName = archiveInfo.packageName,
                installedSignerDigests = signerDigests(installedInfo),
                archiveSignerDigests = signerDigests(archiveInfo),
            )
        ) {
            return false
        }
        AppUpdateInstallPolicy.acceptsArchiveVersion(
            expectedRemoteVersionCode = expectedVersionCode,
            archiveVersionCode = versionCodeOf(archiveInfo),
            installedVersionCode = versionCodeOf(installedInfo),
        )
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signingInfoFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val fromSigningInfo = packageInfo.signingInfo?.let { signingInfo ->
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            }
            if (!fromSigningInfo.isNullOrEmpty()) {
                fromSigningInfo
            } else {
                packageInfo.signatures.orEmpty()
            }
        } else {
            packageInfo.signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }.toSet()
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
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
