package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import java.io.File
import java.security.MessageDigest

/**
 * WebRTC 原生库自服下载服务。
 *
 * 基础 APK 不含 libjingle_peerconnection_so.so（~9.86MB，app jniLibs.excludes 排除），
 * 客户端首次通话前从本服务下载对应 ABI 的 .so 并 System.load 预加载。
 *
 * 二进制打包在服务端 classpath 资源中（server/src/main/resources/webrtc/...），
 * 首次请求时惰性解压到 STORAGE_DIR/webrtc/ 后走 Ktor respondFile 提供 Range/断点续传支持，
 * 解压产物跨重启复用，避免每次启动重写 ~10MB。
 */
object WebRtcBinaryService {

    /** 服务端当前托管的 ABI（与 app 构建的 ndk abiFilters 一致：仅 arm64-v8a）。 */
    const val SUPPORTED_ABI = "arm64-v8a"

    private const val RESOURCE_PATH = "/webrtc/$SUPPORTED_ABI/libjingle_peerconnection_so.so"

    private val targetFile: File by lazy {
        File(File(ServerConfig.storageDir), "webrtc/$SUPPORTED_ABI/libjingle_peerconnection_so.so")
            .canonicalFile
            .also {
                // 9.304：此前拿 parentFile（webrtc/arm64-v8a）与 webrtc 目录比较永不相等，
                // require 永远抛异常被 resolveFile 吞掉 → 端点永远 503，通话从未可用
                val expectedParent = File(File(ServerConfig.storageDir), "webrtc/$SUPPORTED_ABI").canonicalFile
                require(it.parentFile == expectedParent) {
                    "WebRTC 二进制存储路径非法"
                }
            }
    }

    @Volatile
    private var extracted = false

    @Volatile
    private var cachedSha256: String? = null

    /**
     * 返回可提供下载的 .so 文件；资源缺失或解压失败时返回 null。
     * 惰性解压：仅首次调用执行一次 ~10MB 落盘。
     */
    fun resolveFile(): File? {
        if (extracted && targetFile.isFile) return targetFile
        // 8.50 修复 M4：串行化惰性解压——并发首请求同时写同一 .part 会字节交错，
        // rename 后产出损坏的 .so 并被 extracted 永久固化（通话首拉即失败）
        return synchronized(this) {
            if (extracted && targetFile.isFile) return@synchronized targetFile
            val stream = WebRtcBinaryService::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: return@synchronized null
            try {
                stream.use { input ->
                    val dir = targetFile.parentFile ?: return@synchronized null
                    dir.mkdirs()
                    val tmp = File(dir, targetFile.name + ".part")
                    // 9.304：copyTo 不会 flush/close 目标流，buffered 尾部字节丢失会让 .so 损坏
                    // （客户端 SHA-256 校验拒收）——输出流必须 use 关闭
                    tmp.outputStream().buffered().use { output -> input.copyTo(output) }
                    if (!tmp.renameTo(targetFile)) {
                        // 并发首请求时目标可能已被其他线程落盘，直接复用
                        if (!targetFile.isFile) return@synchronized null
                        tmp.delete()
                    }
                }
                extracted = true
                cachedSha256 = null
                targetFile.takeIf { it.isFile }
            } catch (error: Exception) {
                // 9.304：异常不能再静默吞掉——此前 require 失败/IO 错误全部无声变 503，排查无门
                System.err.println("WebRtcBinaryService resolveFile failed: $error")
                null
            }
        }
    }

    /** .so 的 SHA-256（用于 ETag / 客户端完整性校验），惰性计算并缓存。 */
    fun sha256(file: File): String {
        cachedSha256?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.also { cachedSha256 = it }
    }
}
