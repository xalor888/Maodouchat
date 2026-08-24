package com.maodouchat.server.service

import com.maodouchat.server.config.ServerConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * WebRTC 原生库自服下载服务。
 *
 * 基础 APK 不含 libjingle_peerconnection_so.so（~9.86MB，app jniLibs.excludes 排除），
 * 客户端首次通话前从本服务下载对应 ABI 的 .so 并 System.load 预加载。
 *
 * 二进制打包在服务端 classpath 资源中（server/src/main/resources/webrtc/{abi}/...），
 * 首次请求时惰性解压到 STORAGE_DIR/webrtc/{abi}/ 后走 Ktor respondFile。
 * arm64-v8a 给真机/arm 模拟器；x86_64 给本机带屏 x86 模拟器。
 */
object WebRtcBinaryService {

    /** 默认/产品 ABI（与 release ndk abiFilters 一致）。 */
    const val SUPPORTED_ABI = "arm64-v8a"

    val HOSTED_ABIS: Set<String> = setOf("arm64-v8a", "x86_64")

    private val extracted = ConcurrentHashMap<String, Boolean>()
    private val cachedSha256 = ConcurrentHashMap<String, String>()

    fun isSupported(abi: String): Boolean = abi in HOSTED_ABIS

    /**
     * 返回可提供下载的 .so 文件；资源缺失或解压失败时返回 null。
     */
    fun resolveFile(abi: String = SUPPORTED_ABI): File? {
        if (!isSupported(abi)) return null
        val target = targetFile(abi) ?: return null
        if (extracted[abi] == true && target.isFile) return target
        return synchronized(this) {
            if (extracted[abi] == true && target.isFile) return@synchronized target
            val stream = WebRtcBinaryService::class.java.getResourceAsStream(resourcePath(abi))
                ?: return@synchronized null
            try {
                stream.use { input ->
                    val dir = target.parentFile ?: return@synchronized null
                    dir.mkdirs()
                    val tmp = File(dir, target.name + ".part")
                    tmp.outputStream().buffered().use { output -> input.copyTo(output) }
                    if (!tmp.renameTo(target)) {
                        if (!target.isFile) return@synchronized null
                        tmp.delete()
                    }
                }
                extracted[abi] = true
                cachedSha256.remove(abi)
                target.takeIf { it.isFile }
            } catch (error: Exception) {
                System.err.println("WebRtcBinaryService resolveFile($abi) failed: $error")
                null
            }
        }
    }

    /** .so 的 SHA-256（用于 ETag / X-Content-SHA256），按 ABI 缓存。 */
    fun sha256(file: File, abi: String = SUPPORTED_ABI): String {
        cachedSha256[abi]?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.also { cachedSha256[abi] = it }
    }

    private fun resourcePath(abi: String) = "/webrtc/$abi/libjingle_peerconnection_so.so"

    private fun targetFile(abi: String): File? = try {
        File(File(ServerConfig.storageDir), "webrtc/$abi/libjingle_peerconnection_so.so")
            .canonicalFile
            .also {
                val expectedParent = File(File(ServerConfig.storageDir), "webrtc/$abi").canonicalFile
                require(it.parentFile == expectedParent) { "WebRTC 二进制存储路径非法" }
            }
    } catch (error: Exception) {
        System.err.println("WebRtcBinaryService targetFile($abi) failed: $error")
        null
    }
}
