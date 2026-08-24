package com.maodouchat.call

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import com.maodouchat.network.ApiConfig
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * WebRTC 原生库加载器（自服下载）。
 *
 * 基础 APK 不含 libjingle_peerconnection_so.so（~9.86MB，jniLibs.excludes 排除）。
 * 首次通话前从自有服务器（GET /api/webrtc/lib/{abi}）下载对应 ABI 的 .so 到应用私有目录
 * 并 System.load 预加载。
 *
 * 进程内只下载一次（[loaded] 标志 + [loadMutex] 串行化），后续通话直接命中。
 */
object WebRtcNativeLibraryLoader {
    private const val TAG = "WebRtcNativeLibraryLoader"
    private const val LIBRARY_NAME = "jingle_peerconnection_so"
    private const val LIBRARY_FILE = "lib$LIBRARY_NAME.so"
    private const val SERVER_ROUTE = "/api/webrtc/lib"
    private const val MAX_LIBRARY_BYTES = 32L * 1024 * 1024

    @Volatile
    private var loaded = false

    private val loadMutex = Mutex()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun isLoaded(): Boolean = loaded

    fun libraryPath(context: Context): String = libraryFile(context).absolutePath

    /**
     * 确保 WebRTC 原生库可加载，必须在 PeerConnectionFactory.initialize() 之前调用。
     * 优先级：系统已有库（Play 特性模块）→ 本地已下载文件 → 服务器下载。
     */
    suspend fun ensureLoaded(context: Context): Result<Unit> {
        if (loaded) {
            _progress.value = 100
            return Result.success(Unit)
        }
        return loadMutex.withLock {
            if (loaded) {
                _progress.value = 100
                return@withLock Result.success(Unit)
            }
            withContext(Dispatchers.IO) {
                if (tryLoadByName()) return@withContext loaded(abi = "bundled")
                val target = libraryFile(context)
                if (target.isFile && tryLoad(target.absolutePath)) {
                    return@withContext loaded(abi = "cached", bytes = target.length())
                }
                try {
                    download(target)
                    val abi = WebRtcNativeDownloadPolicy.requestAbi(Build.SUPPORTED_ABIS)
                    if (!tryLoad(target.absolutePath)) {
                        target.delete()
                        throw IllegalStateException("native_library_load_failed")
                    }
                    loaded(abi = abi, bytes = target.length(), sha256 = sha256(target))
                } catch (error: Exception) {
                    Log.w(TAG, "WebRTC native library ensure failed", error)
                    _progress.value = 0
                    Result.failure(error)
                }
            }
        }
    }

    private fun loaded(abi: String, bytes: Long? = null, sha256: String? = null): Result<Unit> {
        loaded = true
        _progress.value = 100
        Log.i(
            TAG,
            "WebRTC native library loaded abi=$abi" +
                (bytes?.let { " bytes=$it" } ?: "") +
                (sha256?.let { " sha256=$it" } ?: "")
        )
        return Result.success(Unit)
    }

    private fun tryLoadByName(): Boolean = try {
        System.loadLibrary(LIBRARY_NAME)
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: Exception) {
        false
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun tryLoad(path: String): Boolean = try {
        System.load(path)
        true
    } catch (error: UnsatisfiedLinkError) {
        Log.w(TAG, "dlopen failed for $path: ${error.message}")
        false
    } catch (_: Exception) {
        false
    }

    private fun libraryFile(context: Context): File =
        File(File(context.filesDir, "webrtc").apply { mkdirs() }, LIBRARY_FILE)

    private fun download(target: File) {
        val abi = WebRtcNativeDownloadPolicy.requestAbi(Build.SUPPORTED_ABIS)
        Log.i(TAG, "downloading WebRTC native lib abi=$abi url=${ApiConfig.BASE_URL}$SERVER_ROUTE/$abi")
        _progress.value = 1
        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}$SERVER_ROUTE/$abi")
            .header("Accept-Encoding", "identity")
            .apply {
                TokenManager.getInstanceOrNull()?.getToken()?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("download_failed_http_${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("empty_download_response")
            val expectedSha256 = WebRtcNativeDownloadPolicy.parseChecksum(
                etag = response.header("ETag"),
                contentSha256 = response.header("X-Content-SHA256")
            ) ?: throw IllegalStateException("missing_etag_checksum")
            val contentLength = body.contentLength().takeIf { it > 0L }
            val tmp = File(target.parentFile, target.name + ".part")
            try {
                body.byteStream().use { input ->
                    tmp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_LIBRARY_BYTES) throw IllegalStateException("library_too_large")
                            output.write(buffer, 0, read)
                            val pct = if (contentLength != null) {
                                ((total * 99L) / contentLength).toInt().coerceIn(1, 99)
                            } else {
                                50
                            }
                            _progress.value = pct
                        }
                    }
                }
                val actualSha256 = sha256(tmp)
                if (actualSha256 != expectedSha256) {
                    throw IllegalStateException("sha256_mismatch")
                }
                if (!tmp.renameTo(target)) {
                    if (!target.isFile) throw IllegalStateException("file_write_failed")
                    tmp.delete()
                }
                Log.i(TAG, "WebRTC native lib saved ${target.length()} bytes sha256=$actualSha256")
            } finally {
                if (tmp.exists() && !target.isFile) tmp.delete()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
