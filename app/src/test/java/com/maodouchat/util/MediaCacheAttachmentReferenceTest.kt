package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G202b：`MediaCache` 的附件引用校验与文件名消毒。
 *
 * 为什么值得单独立项：`MediaCache` 被 40 个文件引用，而这里两个函数是**安全边界**：
 *
 * 1. `EncryptedAttachmentReference` 的校验（attachmentId 形状 / key·iv 长度 /
 *    sha256 十六进制 / 大小与时长上下界）——它决定一个**从消息里读回来的附件引用**
 *    能不能被拿去解密。校验松一点，就是把不可信输入喂给解密路径。
 * 2. `sanitizeFileName`——它把 `\/:*?"<>|` 与控制字符换成 `_`。
 *    这是**路径穿越防御**：附件 id 来自消息，文件名若含 `../` 就能跳出缓存目录。
 *
 * 两个函数都是 private，所以这里经由公开的 `encodeEncryptedAttachmentReference`
 * （非法则抛 `AttachmentCryptoException`）与 `decodeEncryptedAttachmentReference`
 * （非法则返回 null）间接断言。
 */
class MediaCacheAttachmentReferenceTest {

    private fun validRef(
        attachmentId: String = "att_" + "a".repeat(30),
        fileName: String = "photo.jpg",
        mimeType: String = "image/jpeg",
        plainSize: Long = 1_000L,
        cipherSize: Long = 1_056L,
        durationMs: Long? = null,
        keyBase64: String = "k".repeat(44),
        ivBase64: String = "i".repeat(24),
        cipherSha256: String = "c".repeat(64),
        plainSha256: String = "e".repeat(64),
        kind: String = "maodouchat-attachment-v1",
    ) = EncryptedAttachmentReference(
        kind = kind,
        attachmentId = attachmentId,
        keyBase64 = keyBase64,
        ivBase64 = ivBase64,
        cipherSha256 = cipherSha256,
        plainSha256 = plainSha256,
        cipherSize = cipherSize,
        fileName = fileName,
        mimeType = mimeType,
        plainSize = plainSize,
        durationMs = durationMs,
    )

    // ---- 合法引用必须往返 ----

    @Test
    fun validReferenceRoundTrips() {
        val ref = validRef()
        val encoded = MediaCache.encodeEncryptedAttachmentReference(ref)
        assertTrue(encoded.startsWith("{"), "编码结果应是 JSON 对象")
        val decoded = MediaCache.decodeEncryptedAttachmentReference(encoded)
        assertNotNull(decoded, "合法引用应能解回来")
        assertEquals(ref.attachmentId, decoded.attachmentId)
        assertEquals(ref.keyBase64, decoded.keyBase64)
        assertEquals(ref.plainSha256, decoded.plainSha256)
        assertEquals(ref.cipherSize, decoded.cipherSize)
    }

    // ---- 非法引用必须被拒（编码抛异常 / 解码返回 null）----

    @Test
    fun encodeRejectsMalformedAttachmentIds() {
        listOf(
            "att_short",              // 太短
            "att_" + "a".repeat(101),  // 太长
            "nope_" + "a".repeat(30),  // 前缀错
            "att_" + "a".repeat(29) + "!", // 含非法字符
            "../../etc/passwd",       // 路径穿越企图
        ).forEach { bad ->
            assertFailsWith<Exception>("attachmentId=$bad 必须被拒") {
                MediaCache.encodeEncryptedAttachmentReference(validRef(attachmentId = bad))
            }
        }
    }

    @Test
    fun encodeRejectsBadHashHexAndLengths() {
        // sha256 必须是 64 位小写十六进制
        assertFailsWith<Exception>("cipherSha256 非十六进制必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(cipherSha256 = "z".repeat(64)))
        }
        assertFailsWith<Exception>("cipherSha256 大写必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(cipherSha256 = "C".repeat(64)))
        }
        assertFailsWith<Exception>("cipherSha256 长度不对必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(cipherSha256 = "c".repeat(63)))
        }
        // key / iv 长度上下界
        assertFailsWith<Exception>("keyBase64 过短必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(keyBase64 = "k".repeat(39)))
        }
        assertFailsWith<Exception>("keyBase64 过长必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(keyBase64 = "k".repeat(49)))
        }
        assertFailsWith<Exception>("ivBase64 过短必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(ivBase64 = "i".repeat(15)))
        }
    }

    @Test
    fun encodeRejectsOutOfRangeSizesAndDurations() {
        assertFailsWith<Exception>("plainSize=0 必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(plainSize = 0L))
        }
        assertFailsWith<Exception>("plainSize 为负必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(plainSize = -1L))
        }
        assertFailsWith<Exception>("cipherSize 低于 17 必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(cipherSize = 16L))
        }
        // 语音时长：null 合法（非语音），给出了就必须 >= 500ms
        MediaCache.encodeEncryptedAttachmentReference(validRef(durationMs = null))
        assertFailsWith<Exception>("durationMs=499 必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(durationMs = 499L))
        }
        assertFailsWith<Exception>("durationMs 超过 1 小时必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(durationMs = 60L * 60L * 1_000L + 1))
        }
    }

    @Test
    fun encodeRejectsWrongKindAndBlankFields() {
        assertFailsWith<Exception>("kind 不对必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(kind = "other-kind"))
        }
        listOf("", "   ").forEach { blank ->
            assertFailsWith<Exception>("fileName 空白必须被拒（原值 blank）") {
                MediaCache.encodeEncryptedAttachmentReference(validRef(fileName = blank))
            }
            assertFailsWith<Exception>("mimeType 空白必须被拒") {
                MediaCache.encodeEncryptedAttachmentReference(validRef(mimeType = blank))
            }
        }
        assertFailsWith<Exception>("fileName 超长必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(fileName = "x".repeat(121)))
        }
    }

    @Test
    fun decodeRejectsNonJsonAndOversizedPayload() {
        // 不是 JSON 对象开头
        assertNull(MediaCache.decodeEncryptedAttachmentReference("plain text"), "非 JSON 必须返回 null")
        assertNull(MediaCache.decodeEncryptedAttachmentReference("[1,2,3]"), "JSON 数组必须返回 null")
        // 超过 2048 字节的载荷直接拒（防滥用）
        val huge = "{" + "\"pad\":\"" + "x".repeat(3_000) + "\"}"
        assertNull(MediaCache.decodeEncryptedAttachmentReference(huge), "超长载荷必须返回 null")
    }

    // ---- 路径穿越防御 ----

    @Test
    fun fileNameIsSanitizedAgainstPathTraversal() {
        // 经过 encode → decode 后，文件名里的非法字符必须已换成 '_'
        val nasty = "../../etc/passwd"
        val decoded = MediaCache.decodeEncryptedAttachmentReference(
            MediaCache.encodeEncryptedAttachmentReference(validRef(fileName = nasty))
        )
        assertNotNull(decoded)
        // ⚠️ 这里断言的**不是**「不得残留 ..」——`sanitizeFileName` 的替换表是
        // `[\\/:*?"<>|\p{Cntrl}]`，**不含 '.'**。所以 "../../etc/passwd" 会变成
        // "_.._etc_passwd"：点号留着，但分隔符全没了。
        // 没有分隔符的 ".." 只是文件名里的两个字符，`File(dir, name)` 仍被关在 dir 内，
        // 所以这个防御是**够的**。我第一版按「不得残留 ..」写，失败了——
        // 那是把断言写得比实现更强，不是实现有洞。
        assertFalse(decoded.fileName.contains("/"), "文件名不得残留 '/'（有它才跳得出目录），实际 ${decoded.fileName}")
        assertFalse(decoded.fileName.contains("\\"), "文件名不得残留 '\\'（Windows 分隔符），实际 ${decoded.fileName}")
        // 关键性质：把结果拼到目录后面，必须仍在目录内
        val joined = java.io.File("/tmp/cache-root", decoded.fileName).normalize().path
        assertTrue(joined.startsWith("/tmp/cache-root"), "拼接后必须仍在缓存目录内，实际 $joined")

        // Windows 非法字符与控制字符一并处理
        val win = "a\\b:c*d?e\"f<g>h|i"
        val decoded2 = MediaCache.decodeEncryptedAttachmentReference(
            MediaCache.encodeEncryptedAttachmentReference(validRef(fileName = win))
        )
        assertNotNull(decoded2)
        assertFalse(decoded2.fileName.any { it in "\\/:*?\"<>|" }, "Windows 非法字符必须被替换，实际 ${decoded2.fileName}")
    }

    @Test
    fun mimeTypeIsLowercased() {
        val decoded = MediaCache.decodeEncryptedAttachmentReference(
            MediaCache.encodeEncryptedAttachmentReference(validRef(mimeType = "IMAGE/JPEG"))
        )
        assertNotNull(decoded)
        assertEquals("image/jpeg", decoded.mimeType, "mimeType 必须小写")
    }

    // ---- 附件 URI 往返 ----

    @Test
    fun attachmentUriRoundTrips() {
        val id = "att_" + "b".repeat(30)
        val uri = MediaCache.attachmentUri(id)
        assertEquals("maodou-attachment://$id", uri)
        assertTrue(MediaCache.isRemoteAttachmentUri(uri), "自己的 scheme 必须被认成远端附件")
        assertFalse(MediaCache.isRemoteAttachmentUri("file:///tmp/x.jpg"), "file:// 不是远端附件")
        assertFalse(MediaCache.isRemoteAttachmentUri("https://example.com/a"), "https:// 不是远端附件")
        assertFalse(MediaCache.isRemoteAttachmentUri(""), "空串不是远端附件")
    }


    // ---- attachmentId 长度边界（正则：att_ + 20..100 个字符）----

    @Test
    fun attachmentIdBoundaryLengths() {
        // 恰下界放行
        MediaCache.encodeEncryptedAttachmentReference(validRef(attachmentId = "att_" + "a".repeat(20)))
        // 下界差一拒绝
        assertFailsWith<Exception>("attachmentId 只有 19 个后缀字符必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(attachmentId = "att_" + "a".repeat(19)))
        }
        // 恰上界放行
        MediaCache.encodeEncryptedAttachmentReference(validRef(attachmentId = "att_" + "a".repeat(100)))
    }

    // ---- sanitizeFileName：首尾点裁掉、全点回退 "file"；超长（>120）在 encode 侧直接拒绝 ----

    @Test
    fun fileNameSanitizationTrimsDotsAndCapsLength() {
        val dotted = MediaCache.decodeEncryptedAttachmentReference(
            MediaCache.encodeEncryptedAttachmentReference(validRef(fileName = "...photo..."))
        )
        assertNotNull(dotted)
        assertEquals("photo", dotted.fileName, "首尾的点必须被裁掉")
        val atLimit = MediaCache.decodeEncryptedAttachmentReference(
            MediaCache.encodeEncryptedAttachmentReference(validRef(fileName = "x".repeat(120)))
        )
        assertNotNull(atLimit)
        assertEquals(120, atLimit.fileName.length, "120 字符文件名原样通过")
        assertFailsWith<Exception>("121 字符文件名 encode 必须拒绝") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(fileName = "x".repeat(121)))
        }
        val dots = MediaCache.decodeEncryptedAttachmentReference(
            MediaCache.encodeEncryptedAttachmentReference(validRef(fileName = "..."))
        )
        assertNotNull(dots)
        assertEquals("file", dots.fileName, "全是点的文件名应回退为 file")
    }

    // ---- iv 长度上界 ----

    @Test
    fun ivBase64UpperBoundary() {
        MediaCache.encodeEncryptedAttachmentReference(validRef(ivBase64 = "i".repeat(24)))
        assertFailsWith<Exception>("ivBase64 25 字符必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(ivBase64 = "i".repeat(25)))
        }
    }

    // ---- 解码失败维度：空串与空白串 ----

    @Test
    fun decodeRejectsEmptyAndBlankStrings() {
        assertNull(MediaCache.decodeEncryptedAttachmentReference(""), "空串必须返回 null")
        assertNull(MediaCache.decodeEncryptedAttachmentReference("   "), "空白串必须返回 null")
    }

    // ---- 解码兼容与严格：未知键忽略、kind 被篡改则拒 ----

    @Test
    fun decodeIgnoresUnknownKeysButRejectsTamperedKind() {
        val encoded = MediaCache.encodeEncryptedAttachmentReference(validRef())
        val withExtra = encoded.replaceFirst("{", "{\"zzz\":1,")
        assertNotNull(
            MediaCache.decodeEncryptedAttachmentReference(withExtra),
            "未知 JSON 键应被忽略（ignoreUnknownKeys），仍能解回",
        )
        val tamperedKind = encoded.replace("maodouchat-attachment-v1", "other-kind")
        assertNull(
            MediaCache.decodeEncryptedAttachmentReference(tamperedKind),
            "kind 被篡改必须被拒",
        )
    }

    @Test
    fun keyBase64LowerAndUpperBoundariesPass() {
        MediaCache.encodeEncryptedAttachmentReference(validRef(keyBase64 = "k".repeat(40)))
        MediaCache.encodeEncryptedAttachmentReference(validRef(keyBase64 = "k".repeat(48)))
    }

    @Test
    fun plainSizeAtCapPassesAndOneOverFails() {
        MediaCache.encodeEncryptedAttachmentReference(
            validRef(plainSize = MediaCache.MAX_ATTACHMENT_PLAIN_BYTES))
        assertFailsWith<Exception>("plainSize 超过上界 1 字节必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(
                validRef(plainSize = MediaCache.MAX_ATTACHMENT_PLAIN_BYTES + 1))
        }
    }

    @Test
    fun durationMsBoundariesPassAtBothEnds() {
        MediaCache.encodeEncryptedAttachmentReference(validRef(durationMs = 500L))
        MediaCache.encodeEncryptedAttachmentReference(
            validRef(durationMs = MediaCache.MAX_VOICE_DURATION_MS))
    }

    @Test
    fun mimeTypeAtLengthLimitLowercasesAndOneOverFails() {
        val decoded = MediaCache.decodeEncryptedAttachmentReference(
            MediaCache.encodeEncryptedAttachmentReference(validRef(mimeType = "A".repeat(100))))
        assertNotNull(decoded)
        assertEquals("a".repeat(100), decoded.mimeType, "100 字符 mimeType 应小写后原长通过")
        assertFailsWith<Exception>("mimeType 101 字符必须被拒") {
            MediaCache.encodeEncryptedAttachmentReference(validRef(mimeType = "a".repeat(101)))
        }
    }

    @Test
    fun decodeRejectsTamperedKeyHashAndCipherSize() {
        val encoded = MediaCache.encodeEncryptedAttachmentReference(validRef())
        val badKey = encoded.replace("k".repeat(44), "k".repeat(39))
        assertNull(
            MediaCache.decodeEncryptedAttachmentReference(badKey),
            "keyBase64 被截到 39 字符必须被拒",
        )
        val badHash = encoded.replace("e".repeat(64), "E".repeat(64))
        assertNull(
            MediaCache.decodeEncryptedAttachmentReference(badHash),
            "plainSha256 大写必须被拒",
        )
        val badCipher = encoded.replace("1056", "16")
        assertNull(
            MediaCache.decodeEncryptedAttachmentReference(badCipher),
            "cipherSize 被改到 16 必须被拒",
        )
    }

}
