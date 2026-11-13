package com.maodouchat.domain.messaging

import com.maodouchat.core.model.EncryptedAttachmentReference
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 加密附件引用编解码测试：由 app 侧 MediaCacheAttachmentReferenceTest（除 attachmentUri 一例外）
// 与 MediaCacheAttachmentSizeLimitTest 的两例解码边界测试随迁；引用构造另补 6 例。
class EncryptedAttachmentReferenceCodecTest {

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
        val encoded = encodeEncryptedAttachmentReference(ref)
        assertTrue(encoded.startsWith("{"), "编码结果应是 JSON 对象")
        val decoded = decodeEncryptedAttachmentReference(encoded)
        assertNotNull(decoded, "合法引用应能解回来")
        assertEquals(ref.attachmentId, decoded.attachmentId)
        assertEquals(ref.keyBase64, decoded.keyBase64)
        assertEquals(ref.plainSha256, decoded.plainSha256)
        assertEquals(ref.cipherSize, decoded.cipherSize)
    }

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
                encodeEncryptedAttachmentReference(validRef(attachmentId = bad))
            }
        }
    }

    @Test
    fun encodeRejectsBadHashHexAndLengths() {
        // sha256 必须是 64 位小写十六进制
        assertFailsWith<Exception>("cipherSha256 非十六进制必须被拒") {
            encodeEncryptedAttachmentReference(validRef(cipherSha256 = "z".repeat(64)))
        }
        assertFailsWith<Exception>("cipherSha256 大写必须被拒") {
            encodeEncryptedAttachmentReference(validRef(cipherSha256 = "C".repeat(64)))
        }
        assertFailsWith<Exception>("cipherSha256 长度不对必须被拒") {
            encodeEncryptedAttachmentReference(validRef(cipherSha256 = "c".repeat(63)))
        }
        // key / iv 长度上下界
        assertFailsWith<Exception>("keyBase64 过短必须被拒") {
            encodeEncryptedAttachmentReference(validRef(keyBase64 = "k".repeat(39)))
        }
        assertFailsWith<Exception>("keyBase64 过长必须被拒") {
            encodeEncryptedAttachmentReference(validRef(keyBase64 = "k".repeat(49)))
        }
        assertFailsWith<Exception>("ivBase64 过短必须被拒") {
            encodeEncryptedAttachmentReference(validRef(ivBase64 = "i".repeat(15)))
        }
    }

    @Test
    fun encodeRejectsOutOfRangeSizesAndDurations() {
        assertFailsWith<Exception>("plainSize=0 必须被拒") {
            encodeEncryptedAttachmentReference(validRef(plainSize = 0L))
        }
        assertFailsWith<Exception>("plainSize 为负必须被拒") {
            encodeEncryptedAttachmentReference(validRef(plainSize = -1L))
        }
        assertFailsWith<Exception>("cipherSize 低于 17 必须被拒") {
            encodeEncryptedAttachmentReference(validRef(cipherSize = 16L))
        }
        // 语音时长：null 合法（非语音），给出了就必须 >= 500ms
        encodeEncryptedAttachmentReference(validRef(durationMs = null))
        assertFailsWith<Exception>("durationMs=499 必须被拒") {
            encodeEncryptedAttachmentReference(validRef(durationMs = 499L))
        }
        assertFailsWith<Exception>("durationMs 超过 1 小时必须被拒") {
            encodeEncryptedAttachmentReference(validRef(durationMs = 60L * 60L * 1_000L + 1))
        }
    }

    @Test
    fun encodeRejectsWrongKindAndBlankFields() {
        assertFailsWith<Exception>("kind 不对必须被拒") {
            encodeEncryptedAttachmentReference(validRef(kind = "other-kind"))
        }
        listOf("", "   ").forEach { blank ->
            assertFailsWith<Exception>("fileName 空白必须被拒（原值 blank）") {
                encodeEncryptedAttachmentReference(validRef(fileName = blank))
            }
            assertFailsWith<Exception>("mimeType 空白必须被拒") {
                encodeEncryptedAttachmentReference(validRef(mimeType = blank))
            }
        }
        assertFailsWith<Exception>("fileName 超长必须被拒") {
            encodeEncryptedAttachmentReference(validRef(fileName = "x".repeat(121)))
        }
    }

    @Test
    fun decodeRejectsNonJsonAndOversizedPayload() {
        // 不是 JSON 对象开头
        assertNull(decodeEncryptedAttachmentReference("plain text"), "非 JSON 必须返回 null")
        assertNull(decodeEncryptedAttachmentReference("[1,2,3]"), "JSON 数组必须返回 null")
        // 超过 2048 字节的载荷直接拒（防滥用）
        val huge = "{" + "\"pad\":\"" + "x".repeat(3_000) + "\"}"
        assertNull(decodeEncryptedAttachmentReference(huge), "超长载荷必须返回 null")
    }

    @Test
    fun fileNameIsSanitizedAgainstPathTraversal() {
        // 经过 encode → decode 后，文件名里的非法字符必须已换成 '_'
        val nasty = "../../etc/passwd"
        val decoded = decodeEncryptedAttachmentReference(
            encodeEncryptedAttachmentReference(validRef(fileName = nasty))
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
        val decoded2 = decodeEncryptedAttachmentReference(
            encodeEncryptedAttachmentReference(validRef(fileName = win))
        )
        assertNotNull(decoded2)
        assertFalse(decoded2.fileName.any { it in "\\/:*?\"<>|" }, "Windows 非法字符必须被替换，实际 ${decoded2.fileName}")
    }

    @Test
    fun mimeTypeIsLowercased() {
        val decoded = decodeEncryptedAttachmentReference(
            encodeEncryptedAttachmentReference(validRef(mimeType = "IMAGE/JPEG"))
        )
        assertNotNull(decoded)
        assertEquals("image/jpeg", decoded.mimeType, "mimeType 必须小写")
    }

    @Test
    fun attachmentIdBoundaryLengths() {
        // 恰下界放行
        encodeEncryptedAttachmentReference(validRef(attachmentId = "att_" + "a".repeat(20)))
        // 下界差一拒绝
        assertFailsWith<Exception>("attachmentId 只有 19 个后缀字符必须被拒") {
            encodeEncryptedAttachmentReference(validRef(attachmentId = "att_" + "a".repeat(19)))
        }
        // 恰上界放行
        encodeEncryptedAttachmentReference(validRef(attachmentId = "att_" + "a".repeat(100)))
    }

    @Test
    fun fileNameSanitizationTrimsDotsAndCapsLength() {
        val dotted = decodeEncryptedAttachmentReference(
            encodeEncryptedAttachmentReference(validRef(fileName = "...photo..."))
        )
        assertNotNull(dotted)
        assertEquals("photo", dotted.fileName, "首尾的点必须被裁掉")
        val atLimit = decodeEncryptedAttachmentReference(
            encodeEncryptedAttachmentReference(validRef(fileName = "x".repeat(120)))
        )
        assertNotNull(atLimit)
        assertEquals(120, atLimit.fileName.length, "120 字符文件名原样通过")
        assertFailsWith<Exception>("121 字符文件名 encode 必须拒绝") {
            encodeEncryptedAttachmentReference(validRef(fileName = "x".repeat(121)))
        }
        val dots = decodeEncryptedAttachmentReference(
            encodeEncryptedAttachmentReference(validRef(fileName = "..."))
        )
        assertNotNull(dots)
        assertEquals("file", dots.fileName, "全是点的文件名应回退为 file")
    }

    @Test
    fun ivBase64UpperBoundary() {
        encodeEncryptedAttachmentReference(validRef(ivBase64 = "i".repeat(24)))
        assertFailsWith<Exception>("ivBase64 25 字符必须被拒") {
            encodeEncryptedAttachmentReference(validRef(ivBase64 = "i".repeat(25)))
        }
    }

    @Test
    fun decodeRejectsEmptyAndBlankStrings() {
        assertNull(decodeEncryptedAttachmentReference(""), "空串必须返回 null")
        assertNull(decodeEncryptedAttachmentReference("   "), "空白串必须返回 null")
    }

    @Test
    fun decodeIgnoresUnknownKeysButRejectsTamperedKind() {
        val encoded = encodeEncryptedAttachmentReference(validRef())
        val withExtra = encoded.replaceFirst("{", "{\"zzz\":1,")
        assertNotNull(
            decodeEncryptedAttachmentReference(withExtra),
            "未知 JSON 键应被忽略（ignoreUnknownKeys），仍能解回",
        )
        val tamperedKind = encoded.replace("maodouchat-attachment-v1", "other-kind")
        assertNull(
            decodeEncryptedAttachmentReference(tamperedKind),
            "kind 被篡改必须被拒",
        )
    }

    @Test
    fun keyBase64LowerAndUpperBoundariesPass() {
        encodeEncryptedAttachmentReference(validRef(keyBase64 = "k".repeat(40)))
        encodeEncryptedAttachmentReference(validRef(keyBase64 = "k".repeat(48)))
    }

    @Test
    fun plainSizeAtCapPassesAndOneOverFails() {
        encodeEncryptedAttachmentReference(
            validRef(plainSize = (100L * 1024L * 1024L)))
        assertFailsWith<Exception>("plainSize 超过上界 1 字节必须被拒") {
            encodeEncryptedAttachmentReference(
                validRef(plainSize = (100L * 1024L * 1024L) + 1))
        }
    }

    @Test
    fun durationMsBoundariesPassAtBothEnds() {
        encodeEncryptedAttachmentReference(validRef(durationMs = 500L))
        encodeEncryptedAttachmentReference(
            validRef(durationMs = (60L * 60L * 1_000L)))
    }

    @Test
    fun mimeTypeAtLengthLimitLowercasesAndOneOverFails() {
        val decoded = decodeEncryptedAttachmentReference(
            encodeEncryptedAttachmentReference(validRef(mimeType = "A".repeat(100))))
        assertNotNull(decoded)
        assertEquals("a".repeat(100), decoded.mimeType, "100 字符 mimeType 应小写后原长通过")
        assertFailsWith<Exception>("mimeType 101 字符必须被拒") {
            encodeEncryptedAttachmentReference(validRef(mimeType = "a".repeat(101)))
        }
    }

    @Test
    fun decodeRejectsTamperedKeyHashAndCipherSize() {
        val encoded = encodeEncryptedAttachmentReference(validRef())
        val badKey = encoded.replace("k".repeat(44), "k".repeat(39))
        assertNull(
            decodeEncryptedAttachmentReference(badKey),
            "keyBase64 被截到 39 字符必须被拒",
        )
        val badHash = encoded.replace("e".repeat(64), "E".repeat(64))
        assertNull(
            decodeEncryptedAttachmentReference(badHash),
            "plainSha256 大写必须被拒",
        )
        val badCipher = encoded.replace("1056", "16")
        assertNull(
            decodeEncryptedAttachmentReference(badCipher),
            "cipherSize 被改到 16 必须被拒",
        )
    }

    private fun validRefJson(cipherSize: Long): String =
        "{\"kind\":\"maodouchat-attachment-v1\"," +
            "\"attachmentId\":\"att_${"a".repeat(30)}\"," +
            "\"keyBase64\":\"${"k".repeat(44)}\"," +
            "\"ivBase64\":\"${"i".repeat(24)}\"," +
            "\"cipherSha256\":\"${"c".repeat(64)}\"," +
            "\"plainSha256\":\"${"e".repeat(64)}\"," +
            "\"cipherSize\":$cipherSize," +
            "\"fileName\":\"photo.jpg\"," +
            "\"mimeType\":\"image/jpeg\"," +
            "\"plainSize\":1000}"

    @Test
    fun `decode rejects a cipher size just over the cipher cap`() {
        val cipherCap = (100L * 1024L * 1024L + 64L)
        assertNull(
            decodeEncryptedAttachmentReference(validRefJson(cipherCap + 1)),
            "cipherSize 恰超密文上界 1 字节必须被拒",
        )
        assertNotNull(
            decodeEncryptedAttachmentReference(validRefJson(cipherCap)),
            "cipherSize 恰等于密文上界必须放行",
        )
    }

    @Test
    fun `decode cipher size lower boundary is seventeen`() {
        assertNotNull(
            decodeEncryptedAttachmentReference(validRefJson(17)),
            "cipherSize 恰等于 17 必须放行",
        )
        assertNull(
            decodeEncryptedAttachmentReference(validRefJson(16)),
            "cipherSize 16 必须被拒",
        )
    }

    // ---- Message→引用构造 ----

    private fun validMeta(
        attachmentId: String? = "att_" + "a".repeat(30),
        fileName: String? = "photo.jpg",
        fileMimeType: String? = "image/jpeg",
        fileSizeBytes: Long? = 1_000L,
        attachmentCipherSize: Long? = 1_056L,
        voiceDurationMs: Long? = null,
    ) = MessageMeta(
        attachmentId = attachmentId,
        attachmentKeyBase64 = "k".repeat(44),
        attachmentIvBase64 = "i".repeat(24),
        attachmentCipherSha256 = "c".repeat(64),
        attachmentPlainSha256 = "e".repeat(64),
        attachmentCipherSize = attachmentCipherSize,
        fileName = fileName,
        fileMimeType = fileMimeType,
        fileSizeBytes = fileSizeBytes,
        voiceDurationMs = voiceDurationMs,
    )

    private fun validMessage(meta: MessageMeta = validMeta()) =
        Message(id = "m1", chatId = "c1", senderId = "u1", content = "hello", meta = meta)

    @Test
    fun messageToReferenceBuildsFromMeta() {
        val ref = validMessage().toEncryptedAttachmentReference()
        assertNotNull(ref)
        assertEquals("att_" + "a".repeat(30), ref.attachmentId)
        assertEquals("photo.jpg", ref.fileName)
        assertEquals("image/jpeg", ref.mimeType)
        assertEquals(1_000L, ref.plainSize)
        assertEquals(1_056L, ref.cipherSize)
        assertNull(ref.durationMs)
    }

    @Test
    fun messageToReferenceDefaultsMimeType() {
        val ref = validMessage(validMeta(fileMimeType = null)).toEncryptedAttachmentReference()
        assertNotNull(ref)
        assertEquals("application/octet-stream", ref.mimeType)
    }

    @Test
    fun messageToReferenceReturnsNullWhenFieldMissing() {
        assertNull(validMessage(validMeta(attachmentId = null)).toEncryptedAttachmentReference())
        assertNull(validMessage(validMeta(fileName = null)).toEncryptedAttachmentReference())
        assertNull(validMessage(validMeta(fileSizeBytes = null)).toEncryptedAttachmentReference())
    }

    @Test
    fun messageToReferenceReturnsNullWhenReferenceInvalid() {
        assertNull(validMessage(validMeta(attachmentId = "bad-id")).toEncryptedAttachmentReference())
        assertNull(validMessage(validMeta(voiceDurationMs = 100L)).toEncryptedAttachmentReference())
    }

    @Test
    fun messageToReferenceReadsMetaTagFromContent() {
        val message = Message(id = "m1", chatId = "c1", senderId = "u1", content = "hello")
            .withEncodedMeta(validMeta())
        val ref = message.toEncryptedAttachmentReference()
        assertNotNull(ref)
        assertEquals("att_" + "a".repeat(30), ref.attachmentId)
    }

    @Test
    fun messageToReferencePassesThroughDurationAndSanitizesName() {
        val ref = validMessage(validMeta(voiceDurationMs = 30_000L, fileName = "../../etc/passwd"))
            .toEncryptedAttachmentReference()
        assertNotNull(ref)
        assertEquals(30_000L, ref.durationMs)
        assertFalse(ref.fileName.contains("/"), "文件名不得残留 '/'，实际 ${ref.fileName}")
    }

}
