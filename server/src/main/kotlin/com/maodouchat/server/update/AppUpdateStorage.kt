package com.maodouchat.server.update

import com.maodouchat.server.config.ServerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AppUpdateStorage {
    private const val DIR = "app-updates"
    const val FILE_NAME = "latest.apk"
    const val MANIFEST_NAME = "latest.manifest.json"

    fun latestFile(): File = File(typeRoot(), FILE_NAME)

    fun latestSha256(): String? {
        val file = latestFile()
        if (!file.isFile) return null
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

    fun saveFromStream(input: InputStream, maxBytes: Long = AppUpdatePublishPolicy.MAX_APK_BYTES): File {
        val dir = typeRoot()
        val tmp = File(dir, "$FILE_NAME.tmp")
        runCatching { if (tmp.exists()) tmp.delete() }
        var total = 0L
        val magic = ByteArray(4)
        var magicRead = 0
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                if (magicRead < 4) {
                    val copy = minOf(4 - magicRead, n)
                    System.arraycopy(buf, 0, magic, magicRead, copy)
                    magicRead += copy
                }
                total += n
                if (total > maxBytes) {
                    tmp.delete()
                    error("too_large")
                }
                out.write(buf, 0, n)
            }
            out.flush()
        }
        if (magicRead < 4 || !AppUpdatePublishPolicy.isZipMagic(magic)) {
            tmp.delete()
            error("not_apk")
        }
        if (total < AppUpdatePublishPolicy.MIN_APK_BYTES) {
            tmp.delete()
            error("too_small")
        }
        val dest = latestFile()
        try {
            Files.move(
                tmp.toPath(),
                dest.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return dest
    }

    private fun typeRoot(): File {
        val root = File(ServerConfig.storageDir).canonicalFile
        require(root.isDirectory || root.mkdirs()) { "storage root missing: ${root.path}" }
        val dir = File(root, DIR)
        require(dir.isDirectory || dir.mkdirs()) { "app-updates dir missing: ${dir.path}" }
        val canonicalDir = dir.canonicalFile
        val rootPath = root.toPath()
        val dirPath = canonicalDir.toPath()
        require(canonicalDir.name == DIR && dirPath.startsWith(rootPath)) {
            "app-updates path illegal: dir=${canonicalDir.path} root=${root.path}"
        }
        return canonicalDir
    }

    /** B14：不可变制品元数据——发布时原子写入 manifest 旁文件（与 APK 同目录，仅发布流程可写）。 */
    fun writeManifest(versionCode: Int, versionName: String, apkSha256: String, bytes: Long) {
        // B14：制品签名——对确定性字段行做 HMAC-SHA256（key=JWT_SECRET），客户端据此验签。
        val canonical = "versionCode=$versionCode\nversionName=$versionName\napkSha256=$apkSha256\nbytes=$bytes"
        val signature = hmacSha256Hex(ServerConfig.jwtSecret, canonical)
        val json = buildJsonObject {
            put("versionCode", versionCode)
            put("versionName", versionName)
            put("apkSha256", apkSha256)
            put("bytes", bytes)
            put("publishedAt", System.currentTimeMillis())
            put("signature", signature)
        }.toString()
        val tmp = File(typeRoot(), "$MANIFEST_NAME.tmp")
        tmp.writeText(json)
        try {
            Files.move(
                tmp.toPath(),
                manifestFile().toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), manifestFile().toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hmacSha256Hex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** B14：读取已发布制品的不可变元数据（无发布时返回 null）。 */
    fun readManifest(): Map<String, String>? {
        val file = manifestFile()
        if (!file.isFile) return null
        return runCatching {
            Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
        }.getOrNull()
    }

    private fun manifestFile(): File = File(typeRoot(), MANIFEST_NAME)
}
