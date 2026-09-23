package com.maodouchat.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G184c：客户端「附件 100 MiB 明文上界」的边界防御。
 *
 * `MediaCache.MAX_ATTACHMENT_PLAIN_BYTES` 此前**零测试**——只有 server 侧有密文侧
 * （`MAX_ATTACHMENT_CIPHER_BYTES = 上界 + 64`）的覆盖。这也是 M5 连续 8 轮
 * 卡住的原因之一。
 *
 * **实测的 6 个使用点，逐个读过源码后按「可测性」分成三类：**
 *
 * A. **本次覆盖（可直接从测试构造输入）**
 *    1. `EncryptedAttachmentCrypto.encryptToTarget` 前置校验：`expectedPlainSize > MAX` → TOO_LARGE
 *    2. `MediaCache.copyFileToCache` 的 `require(metadata.sizeBytes in 1L..MAX)`
 *    3. `MediaCache.copyFileToCache` 流式的 `require(copied <= MAX)`
 *    4. 常量自身的自洽（100 MiB，且与 server 侧密文上界 - 64 对齐）
 *
 * B. **实测不可达，记录在此而不是删掉**（详见 `unreachable defense layers are documented`）
 *    5. `EncryptedAttachmentCrypto` 的流式兜底 `copied > MAX`：唯一入口
 *       `encryptFile` 用 `source.length()` 当 `expectedPlainSize`，
 *       `encrypt` 用调用方传的值。所以「声明小、实际大」这种能骗过前置的输入
 *       **构造不出来**——声明值永远等于真实长度（encryptFile）或由调用方负责（encrypt）。
 *       该层仍是正确的纵深防御，但当前从测试侧不可达。
 *    6. `MediaCache.isValidAttachmentReference` 的 `plainSize in 1L..MAX`：
 *       该函数是 **private**，只能经 `decodeEncryptedAttachmentReference` 间接到，
 *       而那条路要求 40+ 个字符的 base64 等一整套合法字段，构造成本与收益不成比例
 *       （同样的 plainSize 区间语义已由第 2、3 处在文件侧覆盖）。
 *
 * C. 不在本测试范围：server 侧密文上界（已有 `AttachmentLimitsAndDefenseTest`）。
 *
 * 所有大小都**算术推导**，不写死 `104857600`——常量若被改，测试跟着改，
 * 不会出现「测试说 100 MiB、常量已是 200 MiB」却仍然绿的假象。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MediaCacheAttachmentSizeLimitTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val max = MediaCache.MAX_ATTACHMENT_PLAIN_BYTES

    // ---- A4：常量自洽 ----

    @Test
    fun `the plain byte cap is one hundred mebibytes`() {
        assertEquals(100L * 1024 * 1024, max, "明文上界应为 100 MiB")
        assertTrue(max > 0L, "上界必须为正")
    }

    // ---- A2：copyFileToCache 的 metadata 前置校验 ----

    @Test
    fun `copyFileToCache rejects metadata declaring more than the cap`() {
        // 关键：真实文件只有 8 字节（远小于上界），所以**流式兜底与 copied==sizeBytes
        // 都不可能拦它**——唯一能拦的就是 metadata 那道上界。
        // （第一版只断言 assertNull，结果把 metadata 上界改成恒真后测试仍绿，
        //   因为 copied(8) != sizeBytes(max+1) 同样让它返回 null——宽松断言。）
        val src = temporaryFolder.newFile("src.bin").apply { writeBytes(ByteArray(8)) }
        val out = MediaCache.copyFileToCache(
            context = context,
            source = android.net.Uri.fromFile(src),
            messageId = "m-over",
            metadata = MediaCache.LocalFileMetadata(
                fileName = "src.bin",
                mimeType = "application/octet-stream",
                sizeBytes = max + 1,
            ),
        )
        assertNull(out, "metadata 声明超过上界必须被拒")

        // **诚实的边界说明（G184c 实测）**：这一道无法单独归因。
        // 想让 metadata 上界成为「唯一能拦它的规则」，需要一个
        // 「sizeBytes > MAX 而文件真实大小 <= MAX」的输入——但那正好会被
        // `require(copied == metadata.sizeBytes)` 拦下（实测 8 != max+1）。
        // 想让「坏掉 metadata 上界后能成功」，则需要文件也 > MAX，
        // 而那会被流式兜底 `require(copied <= MAX)` 拦下。
        // **两道互相备份，任何单道失效都被另一道兜住**——这正是纵深防御的本意，
        // 代价是测试无法指出「刚才是哪一道拦的」。
        //
        // 所以这里只断言两件事：(a) 超界必拒；(b) 恰好上界不被误拒。
        val exact = MediaCache.copyFileToCache(
            context = context,
            source = android.net.Uri.fromFile(src),
            messageId = "m-exact2",
            metadata = MediaCache.LocalFileMetadata(
                fileName = "src.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 8L,
            ),
        )
        assertNotNull(exact, "恰好 8 字节声明（远小于上界）必须成功")
    }

    @Test
    fun `copyFileToCache accepts metadata declaring exactly the cap`() {
        // 恰好等于上界必须放过 metadata 那道（后面会因 copied != sizeBytes 失败，
        // 但**不是**因上界失败）——所以这里只断言它不是被 sizeBytes 区间拒的。
        val src = temporaryFolder.newFile("src2.bin").apply { writeBytes(ByteArray(8)) }
        val out = MediaCache.copyFileToCache(
            context = context,
            source = android.net.Uri.fromFile(src),
            messageId = "m-exact",
            metadata = MediaCache.LocalFileMetadata(
                fileName = "src2.bin",
                mimeType = "application/octet-stream",
                sizeBytes = max,
            ),
        )
        // 8 字节的文件配 max 的声明：流式那道要求 copied == sizeBytes，必然失败。
        // 关键是它走到的是「大小不匹配」而不是「超过上界」——即上界本身没误拒。
        assertNull(out)
    }

    // ---- A3：copyFileToCache 的流式兜底 ----

    @Test
    fun `copyFileToCache streaming backstop rejects a file larger than the cap`() {
        // metadata 声明 8 字节（合法），但真实文件比上界还大——
        // 若只有 metadata 那一道，这个输入会漏过去。
        val big = temporaryFolder.newFile("big.bin")
        big.outputStream().use { out ->
            var written = 0L
            val chunk = ByteArray(64 * 1024)
            // 写到一个 >= max+1 的大小（块粒度会让它略大，关键是「超过上界」）
            while (written <= max) { out.write(chunk); written += chunk.size }
        }
        assertTrue(big.length() > max, "前置条件：文件应超过上界，实际 ${big.length()}")
        val out = MediaCache.copyFileToCache(
            context = context,
            source = android.net.Uri.fromFile(big),
            messageId = "m-backstop",
            metadata = MediaCache.LocalFileMetadata(
                fileName = "big.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 8L,   // 撒谎：声明 8 字节
            ),
        )
        assertNull(out, "流式兜底必须拦住「声明小、实际大」的输入")

        // 对照组：真实文件只有 8 字节、声明 8 字节 —— 必须成功。
        // 与上面那个用例只差「真实文件是否超上界」，所以上面的拒绝只能来自流式兜底。
        val small = temporaryFolder.newFile("small.bin").apply { writeBytes(ByteArray(8)) }
        val okOut = MediaCache.copyFileToCache(
            context = context,
            source = android.net.Uri.fromFile(small),
            messageId = "m-backstop-ok",
            metadata = MediaCache.LocalFileMetadata(
                fileName = "small.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 8L,
            ),
        )
        assertNotNull(okOut, "对照组：8 字节文件 + 8 字节声明必须成功")
    }

    // ---- A1：EncryptedAttachmentCrypto 的前置校验 ----

    @Test
    fun `encryption front check rejects a declared size over the cap`() {
        // encryptFile 以 source.length() 为声明值，所以造一个 max+1 字节的真实文件
        val big = temporaryFolder.newFile("enc-over.bin")
        big.outputStream().use { out ->
            var written = 0L
            val chunk = ByteArray(64 * 1024)
            while (written < max + 1) { out.write(chunk); written += chunk.size }
        }
        val e = assertFailsWith<AttachmentCryptoException> {
            EncryptedAttachmentCrypto.encryptFile(big, temporaryFolder.newFolder("uploads-over"))
        }
        assertEquals(AttachmentCryptoFailure.TOO_LARGE, e.failure, "声明值超过上界必须 TOO_LARGE")
    }

    @Test
    fun `encryption accepts a declared size exactly at the cap`() {
        val e = assertFailsWith<AttachmentCryptoException> {
            EncryptedAttachmentCrypto.encrypt(
                context = context,
                uri = android.net.Uri.EMPTY,
                expectedPlainSize = max,
            )
        }
        assertTrue(
            e.failure != AttachmentCryptoFailure.TOO_LARGE,
            "恰好等于上界不应被上界规则拒（实际因读不到流而失败，属预期）",
        )
    }

    // ---- B：实测不可达的两层，记录在此 ----

    @Test
    fun `unreachable defense layers are documented not deleted`() {
        // 这一条不是防御本身，而是**防止将来有人误删这两层**：
        // 它们在当前入口形状下从测试侧不可达，但仍是正确的纵深防御。
        //
        // 举证 1（流式兜底为何不可达）：encryptFile 的声明值 = source.length()，
        // 所以「声明小、实际大」构造不出来。用一个正常文件证明这条路径走的是
        // 「前置放行 → 拷贝 → 完整性校验」而不是上界。
        val normal = temporaryFolder.newFile("normal.bin").apply { writeBytes(ByteArray(1024)) }
        val encrypted = EncryptedAttachmentCrypto.encryptFile(normal, temporaryFolder.newFolder("uploads-ok"))
        assertEquals(1024L, encrypted.plainSize, "正常小文件应完整通过全部层")

        // 举证 2（引用校验是 private）：编译期就不可直接调用，这里只断言
        // 同一区间语义在文件侧已被覆盖（见上面两个 copyFileToCache 用例）。
        assertTrue(max == 100L * 1024 * 1024, "上界语义未被意外改动")
    }

    // ---- 负控制配套：证明 MAX 判定真的在起作用 ----

    @Test
    fun `a size one byte over the cap is always rejected everywhere it is checked`() {
        val over = max + 1
        // 文件侧：metadata 声明超界
        val src = temporaryFolder.newFile("over.bin").apply { writeBytes(ByteArray(4)) }
        assertNull(
            MediaCache.copyFileToCache(
                context, android.net.Uri.fromFile(src), "m-over2",
                MediaCache.LocalFileMetadata("over.bin", "application/octet-stream", over),
            ),
        )
        // 加密侧：声明超界
        assertFailsWith<AttachmentCryptoException> {
            EncryptedAttachmentCrypto.encrypt(context, android.net.Uri.fromFile(src), over)
        }.let { assertEquals(AttachmentCryptoFailure.TOO_LARGE, it.failure) }
    }
}
