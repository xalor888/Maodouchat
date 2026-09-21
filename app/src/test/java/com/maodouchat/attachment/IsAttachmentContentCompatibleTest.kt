package com.maodouchat.attachment

import com.maodouchat.data.model.MessageType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G157：`AttachmentContentInspector.isAttachmentContentCompatible` 的测试。
 *
 * 上传前用文件头几个字节判断「声明的类型和实际内容是否相符」，防止把
 * 文本当图片发出去。每种格式只认它自己的魔数——认宽了会让伪装文件混过去，
 * 认窄了会把合法文件拒掉。
 */
class IsAttachmentContentCompatibleTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(s: String) = ByteArray(s.length) { s[it].code.toByte() }

    @Test
    fun `file type is always compatible`() {
        assertTrue(isAttachmentContentCompatible(MessageType.FILE, ByteArray(0)))
        assertTrue(isAttachmentContentCompatible(MessageType.FILE, ascii("garbage")))
    }

    @Test
    fun `image only accepts the jpeg magic`() {
        assertTrue(isAttachmentContentCompatible(MessageType.IMAGE, bytes(0xFF, 0xD8, 0xFF)))
        assertTrue(isAttachmentContentCompatible(MessageType.IMAGE, bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)))
        // PNG 魔数不是 JPEG
        assertFalse(isAttachmentContentCompatible(MessageType.IMAGE, bytes(0x89, 0x50, 0x4E, 0x47)))
        // 少一个字节
        assertFalse(isAttachmentContentCompatible(MessageType.IMAGE, bytes(0xFF, 0xD8)))
    }

    @Test
    fun `gif accepts both 87a and 89a and is case sensitive`() {
        assertTrue(isAttachmentContentCompatible(MessageType.GIF, ascii("GIF87a")))
        assertTrue(isAttachmentContentCompatible(MessageType.GIF, ascii("GIF89a")))
        assertTrue(isAttachmentContentCompatible(MessageType.GIF, ascii("GIF89a") + byteArrayOf(1, 2, 3)))
        // 小写不算
        assertFalse(isAttachmentContentCompatible(MessageType.GIF, ascii("gif89a")))
        // 其他版本号
        assertFalse(isAttachmentContentCompatible(MessageType.GIF, ascii("GIF90a")))
        // 太短
        assertFalse(isAttachmentContentCompatible(MessageType.GIF, ascii("GIF8")))
    }

    @Test
    fun `video accepts iso base media and matroska`() {
        // ISO BMFF：offset 4..7 是 "ftyp"
        assertTrue(isAttachmentContentCompatible(MessageType.VIDEO, ascii("\u0000\u0000\u0000\u0018ftypmp42")))
        // Matroska / WebM EBML 魔数
        assertTrue(isAttachmentContentCompatible(MessageType.VIDEO, bytes(0x1A, 0x45, 0xDF, 0xA3)))
        assertTrue(isAttachmentContentCompatible(MessageType.VIDEO, bytes(0x1A, 0x45, 0xDF, 0xA3, 0x01, 0x00)))
        // 顺序反了不算
        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, ascii("ftyp\u0000\u0000\u0000\u0018")))
        // 普通 mp3 不算
        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, ascii("ID3\u0003\u0000\u0000\u0000")))
    }

    @Test
    fun `voice accepts iso base media only`() {
        assertTrue(isAttachmentContentCompatible(MessageType.VOICE, ascii("\u0000\u0000\u0000\u0018ftypM4A ")))
        // Matroska 不是语音容器
        assertFalse(isAttachmentContentCompatible(MessageType.VOICE, bytes(0x1A, 0x45, 0xDF, 0xA3)))
        // WAV（RIFF）不在白名单里
        assertFalse(isAttachmentContentCompatible(MessageType.VOICE, ascii("RIFF????WAVE")))
    }

    @Test
    fun `other types are never compatible`() {
        listOf(MessageType.TEXT, MessageType.IMAGE, MessageType.GIF).forEach { t ->
            if (t == MessageType.IMAGE || t == MessageType.GIF) return@forEach
            assertFalse(isAttachmentContentCompatible(t, ascii("anything")))
        }
        // STICKER / LOCATION 等同样走 else 分支
        assertFalse(isAttachmentContentCompatible(MessageType.STICKER, ascii("GIF89a")))
        assertFalse(isAttachmentContentCompatible(MessageType.LOCATION, ascii("\u0000\u0000\u0000\u0018ftypmp42")))
    }

    @Test
    fun `short headers are rejected`() {
        assertFalse(isAttachmentContentCompatible(MessageType.IMAGE, ByteArray(0)))
        assertFalse(isAttachmentContentCompatible(MessageType.GIF, ByteArray(0)))
        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, ByteArray(0)))
        assertFalse(isAttachmentContentCompatible(MessageType.VOICE, ByteArray(0)))
        // 恰好差一位：ISO BMFF 需要 8 字节
        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, ascii("\u0000\u0000\u0000\u0018fty")))
        assertTrue(isAttachmentContentCompatible(MessageType.VIDEO, ascii("\u0000\u0000\u0000\u0018ftyp")))
    }
}
