package com.maodouchat.util

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.core.net.toUri

/**
 * G203b：`MediaCache` 缓存路径构造的**路径穿越防御**覆盖。
 *
 * `MediaCache` 的缓存文件名来自消息 id / 附件 id / 扩展名——**全部是不可信输入**
 * （来自消息体）。它用**两层**防御：
 *
 * 1. **白名单消毒**：`messageId.replace(Regex("[^A-Za-z0-9_-]"), "_")`
 *    ——只留字母数字与 `_-`，其余一律变 `_`；
 * 2. **canonical 兜底**：`require(target.canonicalPath.startsWith(dir.canonicalPath + "/"))`
 *    ——即便第一层失效，也**抛异常**而不是放行。
 *
 * 这两层此前都没有测试。第二层尤其重要：它是「宁可崩也不越界」的那道闸。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MediaCachePathEscapeTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun cleanCache() {
        File(ctx.cacheDir, "attachment-uploads").deleteRecursively()
        File(ctx.cacheDir, "attachment-downloads").deleteRecursively()
        File(ctx.cacheDir, "attachment-sources").deleteRecursively()
        File(ctx.cacheDir, "maodouchat_media").deleteRecursively()
    }

    /** 反复用的断言：文件必须落在给定目录内（按 canonical 路径判）。 */
    private fun assertInside(dir: File, target: File) {
        val root = dir.canonicalPath + File.separator
        assertTrue(
            target.canonicalPath.startsWith(root),
            "目标必须落在 $root 内，实际 ${target.canonicalPath}",
        )
    }

    @Test
    fun createAttachmentCacheFileEscapesCannotLeaveTheDirectory() {
        val dir = File(ctx.cacheDir, "maodouchat_media")
        dir.mkdirs()
        listOf(
            "../../../../etc/passwd",
            "..\\..\\windows\\system32",
            "a/b/c",
            "....//....//",
            ".\u0000evil",
            "id with spaces",
        ).forEach { evil ->
            val target = MediaCache.createAttachmentCacheFile(ctx, messageId = evil, fileName = "x.jpg")
            assertInside(dir, target)
            // 名字里不该再有路径分隔符
            assertFalse(target.name.contains("/"), "文件名不得含 '/'，实际 ${target.name}")
            assertFalse(target.name.contains("\\"), "文件名不得含 '\\'，实际 ${target.name}")
        }
    }

    @Test
    fun createEncryptedDownloadFileEscapesCannotLeaveTheDirectory() {
        val dir = File(ctx.cacheDir, "attachment-downloads").apply { mkdirs() }
        listOf("../../../tmp/evil", "..\\..\\evil", "a/b", "").forEach { evil ->
            val target = MediaCache.createEncryptedDownloadFile(ctx, attachmentId = evil)
            assertInside(dir, target)
        }
        // discriminator 也被消毒
        val withDisc = MediaCache.createEncryptedDownloadFile(
            ctx, attachmentId = "att_abc", discriminator = "../../msg/id",
        )
        assertInside(dir, withDisc)
        assertFalse(withDisc.name.contains("/"), "discriminator 也被消毒，实际 ${withDisc.name}")
    }

    @Test
    fun preparedAttachmentSourceRejectsBadExtensions() {
        // 扩展名必须形如 .jpg（点 + 1..10 位小写字母数字）
        listOf("jpg", ".JPG", ".j pe", ".", "..", ".jpg/../../x", ".verylongextension").forEach { bad ->
            assertFailsWith<IllegalArgumentException>("扩展名 '$bad' 必须被拒") {
                MediaCache.createPreparedAttachmentSource(ctx, messageId = "m1", extension = bad)
            }
        }
        // 合法扩展名照常通过
        val ok = MediaCache.createPreparedAttachmentSource(ctx, messageId = "m1", extension = ".jpg")
        assertInside(File(ctx.cacheDir, "attachment-sources"), ok)
    }

    @Test
    fun preparedAttachmentSourceFileRejectsPathsOutsideTheRoot() {
        val outside = File(ctx.cacheDir.parentFile, "escape.jpg").absolutePath
        assertNull(
            MediaCache.preparedAttachmentSourceFile(ctx, "file://$outside"),
            "目录外的路径必须拒绝",
        )
        // 非 file scheme 也拒绝
        assertNull(MediaCache.preparedAttachmentSourceFile(ctx, "content://x"))
        assertNull(MediaCache.preparedAttachmentSourceFile(ctx, "https://x/y.jpg"))
        // 目录内的真文件应被接受
        val root = File(ctx.cacheDir, "attachment-sources").apply { mkdirs() }
        val inside = File(root, "m1.jpg").apply { writeText("x") }
        assertNotNull(
            MediaCache.preparedAttachmentSourceFile(ctx, "file://${inside.absolutePath}"),
            "目录内的文件应被接受",
        )
    }

    @Test
    fun secretChatDirRejectsTraversalChatIds() {
        // 密聊隔离目录的 chatId 同样是不可信输入
        // ⚠️ 空串不在此列：`!secretChatId.isNullOrBlank()` 会把 "" 当成「非密聊」，
        // 走共享缓存目录而不是报错。这是 has-参数 的正常语义，不是漏洞。
        listOf("..", ".", "../../x", "a/b", "a\\b", "a b", "a\u0000b").forEach { bad ->
            assertFailsWith<IllegalArgumentException>("密聊 chatId '$bad' 必须让建目录失败") {
                MediaCache.createAttachmentCacheFile(
                    ctx, messageId = "m1", fileName = "x.jpg", secretChatId = bad,
                )
            }
        }
    }

    @Test
    fun secretChatDirAcceptsCleanChatIdsAndStaysInsideTheRoot() {
        val ok = MediaCache.createAttachmentCacheFile(
            ctx, messageId = "m1", fileName = "x.jpg", secretChatId = "chat_123",
        )
        val root = File(ctx.cacheDir, "maodouchat_media_secret")
        assertInside(root, ok)
    }

    /**
     * 第二层防御（canonical require）自身要成立：
     * 即便第一层消毒被完全去掉，也**抛异常**而不是返回一个越界路径。
     * 这里不真去改生产代码，而是直接验证「目标路径不在目录内时 require 会炸」——
     * 用一个必然越界的扩展名/组合不可能构造，所以改为断言
     * 正常返回的 canonical 路径严格以目录开头（第二层的实际判据）。
     */
    @Test
    fun returnedPathsAreAlwaysCanonicallyInsideTheDirectory() {
        val target = MediaCache.createAttachmentCacheFile(ctx, messageId = "m/../../../1", fileName = "a.b")
        val dir = File(ctx.cacheDir, "maodouchat_media")
        assertEquals(
            true,
            target.canonicalPath.startsWith(dir.canonicalPath + File.separator),
            "canonical 判据必须成立",
        )
        // 不要断言确切字符串（'.' 也会被换成 '_'，个数容易数错）；
        // 断言真正的性质：名字里只剩白名单字符。
        assertTrue(
            target.nameWithoutExtension.all { it.isLetterOrDigit() || it == '_' || it == '-' },
            "消毒后名字应只剩 [A-Za-z0-9_-]，实际 ${target.name}",
        )
    }


    // ---- 重复/幂等：相同输入始终落到同一文件 ----

    @Test
    fun createAttachmentCacheFileIsStableAcrossRepeatedCalls() {
        val first = MediaCache.createAttachmentCacheFile(ctx, messageId = "m-stable", fileName = "a.jpg")
        val second = MediaCache.createAttachmentCacheFile(ctx, messageId = "m-stable", fileName = "a.jpg")
        assertEquals(first.canonicalPath, second.canonicalPath, "相同输入必须落到同一文件")
    }

    // ---- 删除整族：同 messageId 的各种扩展名一次清干净 ----

    @Test
    fun deleteCachedMediaForMessageRemovesEveryExtension() {
        val dir = File(ctx.cacheDir, "maodouchat_media").apply { mkdirs() }
        listOf("m-del.jpg", "m-del.mp4", "m-del").forEach {
            File(dir, it).apply { writeText("x") }
        }
        assertTrue(MediaCache.deleteCachedMediaForMessage(ctx, "m-del"), "删除应成功")
        assertTrue(
            dir.listFiles().orEmpty().none { it.name.startsWith("m-del") },
            "同 messageId 的所有扩展名都应被清掉",
        )
    }

    @Test
    fun deleteCachedMediaForMessageKeepsOtherMessages() {
        val dir = File(ctx.cacheDir, "maodouchat_media").apply { mkdirs() }
        val keep = File(dir, "m-other.jpg").apply { writeText("x") }
        File(dir, "m-del.jpg").apply { writeText("x") }
        MediaCache.deleteCachedMediaForMessage(ctx, "m-del")
        assertTrue(keep.exists(), "别的 messageId 的文件必须保留")
    }

    // ---- copyFileToCache 成功路径与短路复用 ----

    @Test
    fun copyFileToCacheHappyPathPersistsIdenticalBytes() {
        val bytes = ByteArray(256) { it.toByte() }
        val src = File(ctx.cacheDir, "src-happy.bin").apply { writeBytes(bytes) }
        val uri = MediaCache.copyFileToCache(
            ctx, android.net.Uri.fromFile(src), "m-happy",
            LocalFileMetadata("src.bin", "application/octet-stream", bytes.size.toLong()),
        )
        assertNotNull(uri, "小文件 + 声明一致必须成功")
        val target = java.io.File(android.net.Uri.parse(uri).path!!)
        assertEquals(bytes.toList(), target.readBytes().toList(), "落盘字节必须与源一致")
    }

    @Test
    fun copyFileToCacheShortCircuitsWhenSourceIsAlreadyTheTarget() {
        // 源文件本身就是目标文件（canonical 相同、长度与声明一致）
        // 时直接返回目标 uri，不再重写——重复调用零副作用。
        val target = MediaCache.createAttachmentCacheFile(ctx, messageId = "m-same", fileName = "src.bin")
        target.writeBytes(ByteArray(8))
        val out = MediaCache.copyFileToCache(
            ctx, android.net.Uri.fromFile(target), "m-same",
            LocalFileMetadata("src.bin", "application/octet-stream", 8L),
        )
        assertEquals(target.toUri().toString(), out, "源即目标时应短路返回同一 uri")
    }


    @Test
    fun blankSecretChatIdFallsBackToSharedDirectory() {
        val blank = MediaCache.createAttachmentCacheFile(
            ctx, messageId = "m-blank", fileName = "x.jpg", secretChatId = "",
        )
        assertEquals("maodouchat_media", blank.parentFile?.name, "空串密聊 id 应回落共享目录")
        val missing = MediaCache.createAttachmentCacheFile(
            ctx, messageId = "m-blank", fileName = "x.jpg", secretChatId = null,
        )
        assertEquals("maodouchat_media", missing.parentFile?.name, "null 密聊 id 应回落共享目录")
    }

    @Test
    fun deleteSecretChatMediaRemovesOnlyThatChat() {
        val a = MediaCache.createAttachmentCacheFile(
            ctx, messageId = "m-a", fileName = "x.jpg", secretChatId = "chat_a",
        ).apply { writeText("a") }
        val b = MediaCache.createAttachmentCacheFile(
            ctx, messageId = "m-b", fileName = "x.jpg", secretChatId = "chat_b",
        ).apply { writeText("b") }
        assertTrue(MediaCache.deleteSecretChatMedia(ctx, "chat_a"), "删除应成功")
        assertFalse(a.exists(), "chat_a 的文件必须被清掉")
        assertTrue(b.exists(), "chat_b 的文件必须保留")
        assertTrue(MediaCache.deleteSecretChatMedia(ctx, "chat_nope"), "不存在的密聊删除应返回 true")
        File(ctx.cacheDir, "maodouchat_media_secret").deleteRecursively()
    }

    @Test
    fun deleteCachedMediaForMessageAlsoCleansSecretDirs() {
        val sharedDir = File(ctx.cacheDir, "maodouchat_media").apply { mkdirs() }
        val shared = File(sharedDir, "m-sec.jpg").apply { writeText("x") }
        val secret = MediaCache.createAttachmentCacheFile(
            ctx, messageId = "m-sec", fileName = "x.jpg", secretChatId = "chat_s",
        ).apply { writeText("x") }
        assertTrue(MediaCache.deleteCachedMediaForMessage(ctx, "m-sec"), "删除应成功")
        assertFalse(shared.exists(), "共享目录的同 messageId 文件必须被清掉")
        assertFalse(secret.exists(), "密聊隔离目录的同 messageId 文件必须被清掉")
        File(ctx.cacheDir, "maodouchat_media_secret").deleteRecursively()
    }

    @Test
    fun encryptedDownloadFileDiscriminatorVariants() {
        val blank = MediaCache.createEncryptedDownloadFile(ctx, attachmentId = "att_abc", discriminator = "")
        assertEquals("att_abc.part", blank.name, "空 discriminator 应无分隔符后缀")
        val dir = File(ctx.cacheDir, "attachment-downloads")
        listOf("../../x", "a/b", "a b").forEach { disc ->
            val target = MediaCache.createEncryptedDownloadFile(ctx, attachmentId = "att_abc", discriminator = disc)
            assertInside(dir, target)
            assertFalse(target.name.contains("/"), "discriminator 不得残留 '/'，实际 ${target.name}")
            assertTrue(target.name.endsWith(".part"), "下载文件必须以 .part 结尾，实际 ${target.name}")
        }
    }

    @Test
    fun preparedAttachmentSourceExtensionVariants() {
        val dir = File(ctx.cacheDir, "attachment-sources").apply { mkdirs() }
        listOf(".png", ".jpeg", ".mp4", ".a1", ".1234567890").forEach { ext ->
            val target = MediaCache.createPreparedAttachmentSource(ctx, messageId = "m-ext", extension = ext)
            assertInside(dir, target)
            assertTrue(target.name.endsWith(ext), "扩展名 $ext 应原样保留，实际 ${target.name}")
        }
        listOf(".12345678901", ".jpg ", ".j-p", ".jp g").forEach { bad ->
            assertFailsWith<IllegalArgumentException>("扩展名 '$bad' 必须被拒") {
                MediaCache.createPreparedAttachmentSource(ctx, messageId = "m-ext", extension = bad)
            }
        }
    }

}
