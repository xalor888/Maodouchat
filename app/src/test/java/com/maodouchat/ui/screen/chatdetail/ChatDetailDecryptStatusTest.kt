package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChatDetailDecryptStatus` 的行为测试（G328c）。
 *
 * 这组判定此前是 `ChatDetailViewModel` 的成员、取文案要走 `getApplication()`，
 * 于是同目录的 `ChatDetailDecryptPolicyTest` 只能写一句
 * 「本机无 Robolectric 测不了」。抽成注入式之后，这里把**真正危险的边界**钉住：
 *
 * - 把用户正常文本误判成「解密失败占位符」→ 同步游标会停住，聊天永远同步不到新消息；
 * - 把真正的失败判成普通消息 → 失败被当成内容渲染出去；
 * - 「能不能跳过失败」判错 → 终局失败会一直挡住队头。
 */
class ChatDetailDecryptStatusTest {

    /** 只实现需要的 4 个方法，判定逻辑本身不碰 Android。 */
    private class FakeGate(
        private val envelopes: Set<String> = emptySet(),
        private val senderKeys: Set<String> = emptySet(),
        private val terminals: Set<String> = emptySet(),
        private val exhausted: Set<String> = emptySet(),
    ) : DecryptEnvelopeGate {
        override fun isEncryptedEnvelope(content: String) = content in envelopes
        override fun isSenderKeyEnvelope(content: String) = content in senderKeys
        override fun isDecryptTerminalFailure(senderId: String, content: String) = content in terminals
        override fun isDecryptRetryExhausted(senderId: String, content: String) = content in exhausted
    }

    private val texts = DecryptTexts(
        failed = "[无法解密]",
        pending = "[等待密钥]",
        sessionMissing = "[缺少会话密钥]",
        identityChanged = "[安全码已变化]",
        groupFailed = "[群解密失败]",
        groupKeyMissing = "[缺少群密钥]",
        groupIdentityChanged = "[群安全码已变化]",
        groupNewer = "[群密钥 epoch 较新]",
        imageFailed = "[无法解密的图片]",
        gifFailed = "[无法解密的 gif]",
        stickerFailed = "[无法解密的贴纸]",
        locationFailed = "[无法解密的位置]",
        videoFailed = "[无法解密的视频]",
        voiceFailed = "[无法解密的语音]",
        fileFailed = "[无法解密的文件]",
    )

    private fun status(gate: DecryptEnvelopeGate = FakeGate()) = ChatDetailDecryptStatus(gate, texts)

    private fun msg(
        content: String,
        type: MessageType = MessageType.TEXT,
        senderId: String = "u1",
    ) = Message(id = "m1", chatId = "c1", senderId = senderId, content = content, type = type, timestamp = 1L)

    // ─── 失败文案 ───

    @Test
    fun `failedTextFor maps each content type to its own text`() {
        val s = status()
        assertEquals("[无法解密的图片]", s.failedTextFor(MessageType.IMAGE))
        assertEquals("[无法解密的 gif]", s.failedTextFor(MessageType.GIF))
        assertEquals("[无法解密的贴纸]", s.failedTextFor(MessageType.STICKER))
        assertEquals("[无法解密的位置]", s.failedTextFor(MessageType.LOCATION))
        assertEquals("[无法解密的视频]", s.failedTextFor(MessageType.VIDEO))
        assertEquals("[无法解密的语音]", s.failedTextFor(MessageType.VOICE))
        assertEquals("[无法解密的文件]", s.failedTextFor(MessageType.FILE))
    }

    @Test
    fun `failedTextFor falls back to the generic text for non-content types`() {
        val s = status()
        // 逐个枚举「非内容承载」的取值，确保新增类型时这条会提醒有人来确认它该走哪支
        for (type in listOf(
            MessageType.TEXT, MessageType.MARKDOWN, MessageType.NUDGE,
            MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED,
        )) {
            assertEquals("$type 应走通用文案", "[无法解密]", s.failedTextFor(type))
        }
    }

    // ─── 占位符判定 ───

    @Test
    fun `blank content is never a placeholder`() {
        val s = status()
        assertFalse(s.isSyncFailurePlaceholder(msg("")))
        assertFalse(s.isSyncFailurePlaceholder(msg("   ")))
    }

    @Test
    fun `any envelope counts as a placeholder even before it is decrypted`() {
        val s = status(FakeGate(envelopes = setOf("ENV"), senderKeys = setOf("SK")))
        assertTrue("1:1 信封在解密前必须算占位符", s.isSyncFailurePlaceholder(msg("ENV")))
        assertTrue("群 sender key 信封同理", s.isSyncFailurePlaceholder(msg("SK")))
    }

    @Test
    fun `known historical placeholders are recognized`() {
        val s = status()
        // 老客户端可能把失败文案当成正文持久化下来（DecryptPlaceholderPolicy 的清单）
        assertTrue(s.isSyncFailurePlaceholder(msg("无法解密")))
        assertTrue(s.isSyncFailurePlaceholder(msg("[encrypted message]")))
        assertTrue(s.isSyncFailurePlaceholder(msg("  [无法解密的群聊消息]  ")))
    }

    @Test
    fun `localized placeholders come from the injected texts`() {
        val s = status()
        for (t in listOf(
            texts.failed, texts.pending, texts.sessionMissing, texts.identityChanged,
            texts.groupFailed, texts.groupKeyMissing, texts.groupIdentityChanged, texts.groupNewer,
        )) {
            assertTrue("注入的本地化占位符应被识别：$t", s.isSyncFailurePlaceholder(msg(t)))
        }
    }

    @Test
    fun `type specific placeholders are recognized`() {
        val s = status()
        assertTrue(s.isSyncFailurePlaceholder(msg(texts.imageFailed, MessageType.IMAGE)))
        assertTrue(s.isSyncFailurePlaceholder(msg(texts.fileFailed, MessageType.FILE)))
    }

    @Test
    fun `ordinary user text is never treated as a placeholder`() {
        // 这是本条判定最重要的一侧：误判会让同步游标停住。
        val s = status()
        for (text in listOf(
            "晚上一起吃饭吗",
            "这个文件解密失败了我再发一次",          // 只是**提到**解密，不是占位符
            "解密的密钥我给你发过去了",
            "hello world",
            "无法解密",                              // 注意：这条在历史清单里，确实算占位符——见下一条测试
        ).dropLast(1)) {
            assertFalse("用户正文不得被判成占位符：$text", s.isSyncFailurePlaceholder(msg(text)))
        }
    }

    @Test
    fun `placeholder matching is exact after trim and lowercase`() {
        val s = status()
        // DecryptPlaceholderPolicy 规范化后再比对：大小写与首尾空白不影响判定
        assertTrue(s.isSyncFailurePlaceholder(msg("  [ENCRYPTED MESSAGE]  ")))
        // 但夹在句子里的同样字样不算（用户真的可能在聊天里讨论它）
        assertFalse(s.isSyncFailurePlaceholder(msg("他说这条是 [encrypted message] 吧")))
    }

    // ─── 能否跳过失败 ───

    @Test
    fun `plain text can never advance past a failure`() {
        // gate 即使把这段文本当作终局失败，非信封内容也必须返回 false——
        // 否则用户正文会被当成"已终结的失败"从游标里跳过。
        val s = status(FakeGate(terminals = setOf("hello"), exhausted = setOf("hello")))
        assertFalse(s.canAdvancePastSyncFailure(msg("hello")))
    }

    @Test
    fun `terminal or exhausted envelopes can advance`() {
        val s = status(
            FakeGate(
                envelopes = setOf("TERM", "EXH"),
                senderKeys = setOf("SK_TERM"),
                terminals = setOf("TERM", "SK_TERM"),
                exhausted = setOf("EXH"),
            ),
        )
        assertTrue("终局失败应可跳过", s.canAdvancePastSyncFailure(msg("TERM")))
        assertTrue("重试耗尽应可跳过", s.canAdvancePastSyncFailure(msg("EXH")))
        assertTrue("群信封的终局失败同理", s.canAdvancePastSyncFailure(msg("SK_TERM")))
    }

    @Test
    fun `recoverable failure must not advance`() {
        // 可恢复的失败（NoSession / 身份未验证）必须留在游标上，等重试。
        val s = status(FakeGate(envelopes = setOf("RECOVERABLE")))
        assertFalse(s.canAdvancePastSyncFailure(msg("RECOVERABLE")))
    }

    @Test
    fun `placeholder judgement and advance judgement are independent`() {
        // 同一个信封：算占位符（不渲染成正文），但不可跳过（等重试）。
        val s = status(FakeGate(envelopes = setOf("ENV")))
        val m = msg("ENV")
        assertTrue(s.isSyncFailurePlaceholder(m))
        assertFalse(s.canAdvancePastSyncFailure(m))
    }
}
