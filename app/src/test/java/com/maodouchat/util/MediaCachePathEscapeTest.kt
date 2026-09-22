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
}
