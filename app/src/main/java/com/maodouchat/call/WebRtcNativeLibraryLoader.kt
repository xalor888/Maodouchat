package com.maodouchat.call

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import com.maodouchat.network.ApiConfig
import kotlinx.coroutines.Dispatchers
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
 * 并 System.load 预加载，随后 PeerConnectionFactory.initialize() 内部的 System.loadLibrary
 * 复用已加载库。
 *
 * 进程内只下载一次（[loaded] 标志 + [loadMutex] 串行化），后续通话直接命中。
 */
object WebRtcNativeLibraryLoader {
    private const val TAG = "WebRtcNativeLibraryLoader"
    private const val LIBRARY_NAME = "jingle_peerconnection_so"
    private const val LIBRARY_FILE = "lib$LIBRARY_NAME.so"
    private const val SERVER_ROUTE = "/api/webrtc/lib"
    // 8.49：下载字节上限——预期 ~10MB，32MB 上限防御服务端异常/被入侵时把私有存储写满
    private const val MAX_LIBRARY_BYTES = 32L * 1024 * 1024

    @Volatile
    private var loaded = false

    // 8.49：串行化首次加载——来电接听 + 后台轮询唤醒可并发触发 ensureLoaded，
    // 无互斥时各自下载一份 ~10MB 到不同 .part 再竞争 rename
    private val loadMutex = Mutex()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** WebRTC 原生库是否已加载成功（进程内复用）。 */
    fun isLoaded(): Boolean = loaded

    /** 9.306：已落盘 .so 的绝对路径（供 PeerConnectionFactory 自定义加载器 System.load 复用同一句柄）。 */
    fun libraryPath(context: Context): String = libraryFile(context).absolutePath

    /**
     * 确保 WebRTC 原生库可加载，必须在 PeerConnectionFactory.initialize() 之前调用。
     * 优先级：系统已有库（Play 特性模块）→ 本地已下载文件 → 服务器下载。
     */
    suspend fun ensureLoaded(context: Context): Result<Unit> {
        if (loaded) return Result.success(Unit)
        return loadMutex.withLock {
            if (loaded) return@withLock Result.success(Unit)
            withContext(Dispatchers.IO) {
                if (tryLoadByName()) return@withContext loaded()
                val target = libraryFile(context)
                if (target.isFile && tryLoad(target.absolutePath)) return@withContext loaded()
                try {
                    download(target)
                    if (!tryLoad(target.absolutePath)) throw IllegalStateException("native_library_load_failed")
                    loaded()
                } catch (error: Exception) {
                    Log.w(TAG, "WebRTC native library ensure failed", error)
                    Result.failure(error)
                }
            }
        }
    }

    private fun loaded(): Result<Unit> {
        loaded = true
        return Result.success(Unit)
    }

    /** 尝试从系统库路径加载（Play Store 特性模块已装 / 已预加载场景）。 */
    private fun tryLoadByName(): Boolean = try {
        System.loadLibrary(LIBRARY_NAME)
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: Exception) {
        false
    }

    /** 尝试从指定绝对路径加载 .so。 */
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun tryLoad(path: String): Boolean = try {
        System.load(path)
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: Exception) {
        false
    }

    private fun libraryFile(context: Context): File =
        File(File(context.filesDir, "webrtc").apply { mkdirs() }, LIBRARY_FILE)

    /** 从服务器下载对应 ABI 的 .so 到应用私有目录，落盘前校验 SHA-256（服务端 ETag）。 */
    private fun download(target: File) {
        val abi = supportedAbi()
        val request = Request.Builder()
            .url("${ApiConfig.BASE_URL}$SERVER_ROUTE/$abi")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("download_failed_http_${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("empty_download_response")
            val expectedSha256 = response.header("ETag")
                ?.trim('"')
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("missing_etag_checksum")
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
                            // 8.49：无上限流式写入可把私有存储写满（服务端异常/被入侵场景）
                            if (total > MAX_LIBRARY_BYTES) throw IllegalStateException("library_too_large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val actualSha256 = sha256(tmp)
                // fail-closed：服务端必须提供 ETag 且内容哈希一致，否则拒绝落盘
                if (actualSha256 != expectedSha256) {
                    throw IllegalStateException("sha256_mismatch")
                }
                if (!tmp.renameTo(target)) {
                    if (!target.isFile) throw IllegalStateException("file_write_failed")
                    tmp.delete()
                }
            } finally {
                if (tmp.exists() && !target.isFile) tmp.delete()
            }
        }
    }

    /**
     * 选择下载 ABI：与服务端 WebRtcBinaryService.SUPPORTED_ABI（arm64-v8a）对齐。
     * 应用 release 仅打包 arm64-v8a；非 arm64 设备请求 arm64 时 System.load 抛 dlopen 错误，
     * 由调用方把异常信息呈现给用户。
     */
    private fun supportedAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" }
            ?: Build.SUPPORTED_ABIS.firstOrNull { it.startsWith("arm64") }
            ?: "arm64-v8a"

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
