// ChatDetailDecryptStatus fuzz (148th block). Oracle reimplements the
// three status functions from production text, including the already
// fuzz-covered delegate it calls: DecryptPlaceholderPolicy.isPlaceholder
// (fuzz-31/fuzz-33 call it inside their own oracles). This block covers
// the status functions themselves, not the delegate. The gate is a fake
// (set membership), exactly like the existing ChatDetailDecryptStatusTest;
// production gate implementations live in the app module and need Signal.
package com.maodouchat.core.crypto

import com.maodouchat.core.model.DecryptTexts
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Transcribed from DecryptPlaceholderPolicy.LEGACY_PLACEHOLDERS; the
// differential drive parses the production set independently and asserts
// it matches this list, so a transcription slip cannot hide.
private val LEGACY_PLACEHOLDERS148 = setOf(
    "无法解密", "解密失败", "群密钥缺失", "密钥缺失",
    "decrypt failed", "session missing", "identity changed",
    "[无法解密的消息]", "[无法解密的群聊消息]", "[加密消息]",
    "[缺少会话密钥，请等待对方重新发送]",
    "[对方安全码已变化，请验证身份]",
    "[群成员安全码已变化，请验证身份]",
    "[缺少群聊 sender key，请等待成员重新发送]",
    "[群聊密钥 epoch 较新，请刷新群信息]",
    "[无法解密的图片]", "[无法解密的 gif]", "[无法解密的贴纸]",
    "[无法解密的位置]", "[无法解密的视频]", "[无法解密的语音]",
    "[无法解密的文件]",
    "[unable to decrypt message]", "[unable to decrypt group message]",
    "[encrypted message]",
    "[missing session key. waiting for resend]",
    "[safety number changed. verify identity]",
    "[member safety number changed. verify identity]",
    "[missing group sender key. waiting for redistribution]",
    "[newer group key epoch. refresh group info]",
    "[unable to decrypt image]", "[unable to decrypt gif]",
    "[unable to decrypt sticker]", "[unable to decrypt location]",
    "[unable to decrypt video]", "[unable to decrypt voice message]",
    "[unable to decrypt file]",
)

private fun isPlaceholderOracle148(content: String, localized: List<String>): Boolean {
    val normalized = content.trim().lowercase()
    if (normalized.isEmpty()) return false
    return normalized in LEGACY_PLACEHOLDERS148 ||
        localized.any { it.trim().lowercase() == normalized }
}

private fun failedTextForOracle148(type: MessageType, texts: DecryptTexts): String = when (type) {
    MessageType.IMAGE -> texts.imageFailed
    MessageType.GIF -> texts.gifFailed
    MessageType.STICKER -> texts.stickerFailed
    MessageType.LOCATION -> texts.locationFailed
    MessageType.VIDEO -> texts.videoFailed
    MessageType.VOICE -> texts.voiceFailed
    MessageType.FILE -> texts.fileFailed
    else -> texts.failed
}

private class FakeGate148(
    val envelopes: Set<String> = emptySet(),
    val senderKeys: Set<String> = emptySet(),
    val terminals: Set<String> = emptySet(),
    val exhausted: Set<String> = emptySet(),
) : DecryptEnvelopeGate {
    override fun isEncryptedEnvelope(content: String) = content in envelopes
    override fun isSenderKeyEnvelope(content: String) = content in senderKeys
    override fun isDecryptTerminalFailure(senderId: String, content: String) = content in terminals
    override fun isDecryptRetryExhausted(senderId: String, content: String) = content in exhausted
}

private fun isSyncFailurePlaceholderOracle148(
    message: Message,
    gate: DecryptEnvelopeGate,
    texts: DecryptTexts,
): Boolean {
    val content = message.content
    if (content.isBlank()) return false
    if (gate.isEncryptedEnvelope(content) || gate.isSenderKeyEnvelope(content)) return true
    return isPlaceholderOracle148(
        content,
        listOf(
            texts.failed, texts.pending, texts.sessionMissing, texts.identityChanged,
            texts.groupFailed, texts.groupKeyMissing, texts.groupIdentityChanged,
            texts.groupNewer, failedTextForOracle148(message.type, texts),
        ),
    )
}

private fun canAdvancePastSyncFailureOracle148(
    message: Message,
    gate: DecryptEnvelopeGate,
): Boolean {
    val content = message.content
    if (!gate.isEncryptedEnvelope(content) && !gate.isSenderKeyEnvelope(content)) return false
    return gate.isDecryptTerminalFailure(message.senderId, content) ||
        gate.isDecryptRetryExhausted(message.senderId, content)
}

private val TEXTS148 = DecryptTexts(
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

private fun msg148(
    content: String,
    type: MessageType = MessageType.TEXT,
    senderId: String = "u1",
) = Message(id = "m1", chatId = "c1", senderId = senderId, content = content, type = type, timestamp = 1L)

// ---------- generators (mirrored by Oracle148 GEN, seed 148001) ----------

private val CONTENT_POOL148 = arrayOf(
    "", " ", "  ", "无法解密", "解密失败", "群密钥缺失", "密钥缺失",
    "decrypt failed", "Decrypt Failed", "DECRYPT FAILED",
    "session missing", "Session Missing", "identity changed",
    "[无法解密的消息]", "[无法解密的群聊消息]", "[加密消息]",
    "[缺少会话密钥，请等待对方重新发送]",
    "[对方安全码已变化，请验证身份]",
    "[群成员安全码已变化，请验证身份]",
    "[缺少群聊 sender key，请等待成员重新发送]",
    "[群聊密钥 epoch 较新，请刷新群信息]",
    "[无法解密的图片]", "[无法解密的 gif]", "[无法解密的贴纸]",
    "[无法解密的位置]", "[无法解密的视频]", "[无法解密的语音]",
    "[无法解密的文件]",
    "[unable to decrypt message]", "[unable to decrypt group message]",
    "[encrypted message]", "[ENCRYPTED MESSAGE]", "  [encrypted message]  ",
    "[missing session key. waiting for resend]",
    "[safety number changed. verify identity]",
    "[member safety number changed. verify identity]",
    "[missing group sender key. waiting for redistribution]",
    "[newer group key epoch. refresh group info]",
    "[unable to decrypt image]", "[unable to decrypt gif]",
    "[unable to decrypt sticker]", "[unable to decrypt location]",
    "[unable to decrypt video]", "[unable to decrypt voice message]",
    "[unable to decrypt file]",
    "晚上一起吃饭吗", "这个文件解密失败了我再发一次", "hello world",
    "他说这条是 [encrypted message] 吧", "x", "无法解密X", "decrypt failed!",
    "[encrypted message]x", "ENV", "SK", "TERM", "EXH", "RECOVERABLE",
    "İ", "ΟΣ", "ΟΣΑ", "AΣ", "Σ", "ŉ",
)

private val TEXT_POOL148 = arrayOf(
    "[无法解密]", "[等待密钥]", "[缺少会话密钥]", "[安全码已变化]",
    "[群解密失败]", "[缺少群密钥]", "[群安全码已变化]", "[群密钥 epoch 较新]",
    "[无法解密的图片]", "[无法解密的 gif]", "[无法解密的贴纸]",
    "[无法解密的位置]", "[无法解密的视频]", "[无法解密的语音]", "[无法解密的文件]",
    "", " ", "other", "[ENCRYPTED MESSAGE]", "decrypt failed",
)

private val GATE_POOL148 = arrayOf(
    "ENV", "SK", "TERM", "EXH", "RECOVERABLE", "hello", "", "无法解密", "x",
)

private fun randomText148(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) {
        when (rnd.nextInt(10)) {
            0 -> sb.append(('a'.code + rnd.nextInt(26)).toChar())
            1 -> sb.append(('0'.code + rnd.nextInt(10)).toChar())
            2 -> sb.append(' ')
            3 -> sb.append(rnd.nextInt(0x20, 0x2FFF).toChar())
            else -> sb.append(CONTENT_POOL148[rnd.nextInt(CONTENT_POOL148.size)])
        }
    }
    return sb.toString()
}

private fun randomContent148(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CONTENT_POOL148[rnd.nextInt(CONTENT_POOL148.size)]
    else randomText148(rnd, 12)

private fun randomTexts148(rnd: Random): DecryptTexts {
    fun pick(): String = TEXT_POOL148[rnd.nextInt(TEXT_POOL148.size)]
    return DecryptTexts(
        failed = pick(), pending = pick(), sessionMissing = pick(),
        identityChanged = pick(), groupFailed = pick(), groupKeyMissing = pick(),
        groupIdentityChanged = pick(), groupNewer = pick(), imageFailed = pick(),
        gifFailed = pick(), stickerFailed = pick(), locationFailed = pick(),
        videoFailed = pick(), voiceFailed = pick(), fileFailed = pick(),
    )
}

private fun randomGate148(rnd: Random): FakeGate148 {
    fun pickSet(): Set<String> {
        val n = rnd.nextInt(0, 4)
        return (0 until n).map { GATE_POOL148[rnd.nextInt(GATE_POOL148.size)] }.toSet()
    }
    return FakeGate148(pickSet(), pickSet(), pickSet(), pickSet())
}

private fun randomMessage148(rnd: Random): Message {
    val types = MessageType.entries
    return Message(
        id = "m" + rnd.nextInt(0, 5),
        chatId = "c" + rnd.nextInt(0, 3),
        senderId = "u" + rnd.nextInt(0, 3),
        content = randomContent148(rnd),
        type = types[rnd.nextInt(types.size)],
        timestamp = rnd.nextLong(),
    )
}

class ChatDetailDecryptStatusFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val s = ChatDetailDecryptStatus(FakeGate148(), TEXTS148)
        // failedTextFor: each content type maps to its own text.
        assertEquals("[无法解密的图片]", failedTextForOracle148(MessageType.IMAGE, TEXTS148))
        assertEquals("[无法解密的图片]", s.failedTextFor(MessageType.IMAGE))
        assertEquals("[无法解密的 gif]", s.failedTextFor(MessageType.GIF))
        assertEquals("[无法解密的贴纸]", s.failedTextFor(MessageType.STICKER))
        assertEquals("[无法解密的位置]", s.failedTextFor(MessageType.LOCATION))
        assertEquals("[无法解密的视频]", s.failedTextFor(MessageType.VIDEO))
        assertEquals("[无法解密的语音]", s.failedTextFor(MessageType.VOICE))
        assertEquals("[无法解密的文件]", s.failedTextFor(MessageType.FILE))
        for (type in listOf(MessageType.TEXT, MessageType.MARKDOWN, MessageType.NUDGE, MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED)) {
            assertEquals("[无法解密]", s.failedTextFor(type))
            assertEquals(failedTextForOracle148(type, TEXTS148), s.failedTextFor(type))
        }
        // isPlaceholder: legacy set, case/space-insensitive after normalize.
        // Production is called directly here (core/model, no gate needed).
        fun checkPlaceholder(content: String, localized: List<String>) {
            assertEquals(
                isPlaceholderOracle148(content, localized),
                DecryptPlaceholderPolicy.isPlaceholder(content, *localized.toTypedArray()),
            )
        }
        checkPlaceholder("无法解密", emptyList())
        assertTrue(isPlaceholderOracle148("无法解密", emptyList()))
        checkPlaceholder("  [ENCRYPTED MESSAGE]  ", emptyList())
        checkPlaceholder("Decrypt Failed", emptyList())
        checkPlaceholder("", emptyList())
        checkPlaceholder("   ", emptyList())
        checkPlaceholder("他说这条是 [encrypted message] 吧", emptyList())
        checkPlaceholder("无法解密X", emptyList())
        checkPlaceholder("decrypt failed!", emptyList())
        assertFalse(isPlaceholderOracle148("他说这条是 [encrypted message] 吧", emptyList()))
        assertFalse(isPlaceholderOracle148("无法解密X", emptyList()))
        // Localized placeholders come from the injected texts.
        checkPlaceholder("[等待密钥]", listOf(TEXTS148.pending))
        checkPlaceholder("[等待密钥]", listOf(TEXTS148.failed))
        assertTrue(isPlaceholderOracle148("[等待密钥]", listOf(TEXTS148.pending)))
        assertFalse(isPlaceholderOracle148("[等待密钥]", listOf(TEXTS148.failed)))
        // isSyncFailurePlaceholder: blank never, envelope always, placeholder by text.
        assertFalse(s.isSyncFailurePlaceholder(msg148("")))
        assertFalse(s.isSyncFailurePlaceholder(msg148("   ")))
        assertTrue(s.isSyncFailurePlaceholder(msg148("无法解密")))
        assertTrue(s.isSyncFailurePlaceholder(msg148("[encrypted message]")))
        assertTrue(s.isSyncFailurePlaceholder(msg148("  [无法解密的群聊消息]  ")))
        assertTrue(s.isSyncFailurePlaceholder(msg148(TEXTS148.pending)))
        assertTrue(s.isSyncFailurePlaceholder(msg148(TEXTS148.imageFailed, MessageType.IMAGE)))
        assertFalse(s.isSyncFailurePlaceholder(msg148("晚上一起吃饭吗")))
        assertFalse(s.isSyncFailurePlaceholder(msg148("这个文件解密失败了我再发一次")))
        assertFalse(s.isSyncFailurePlaceholder(msg148("hello world")))
        assertEquals(isSyncFailurePlaceholderOracle148(msg148("无法解密"), FakeGate148(), TEXTS148), s.isSyncFailurePlaceholder(msg148("无法解密")))
        val env = ChatDetailDecryptStatus(FakeGate148(envelopes = setOf("ENV"), senderKeys = setOf("SK")), TEXTS148)
        assertTrue(env.isSyncFailurePlaceholder(msg148("ENV")))
        assertTrue(env.isSyncFailurePlaceholder(msg148("SK")))
        assertFalse(env.isSyncFailurePlaceholder(msg148("OTHER")))
        // canAdvancePastSyncFailure: needs an envelope AND terminal/exhausted.
        assertFalse(s.canAdvancePastSyncFailure(msg148("TERM")))
        val adv = ChatDetailDecryptStatus(
            FakeGate148(envelopes = setOf("TERM", "EXH"), senderKeys = setOf("SK_TERM"), terminals = setOf("TERM", "SK_TERM"), exhausted = setOf("EXH")),
            TEXTS148,
        )
        assertTrue(adv.canAdvancePastSyncFailure(msg148("TERM")))
        assertTrue(adv.canAdvancePastSyncFailure(msg148("EXH")))
        assertTrue(adv.canAdvancePastSyncFailure(msg148("SK_TERM")))
        assertFalse(adv.canAdvancePastSyncFailure(msg148("hello")))
        val rec = ChatDetailDecryptStatus(FakeGate148(envelopes = setOf("RECOVERABLE")), TEXTS148)
        assertTrue(rec.isSyncFailurePlaceholder(msg148("RECOVERABLE")))
        assertFalse(rec.canAdvancePastSyncFailure(msg148("RECOVERABLE")))
        assertEquals(canAdvancePastSyncFailureOracle148(msg148("TERM"), FakeGate148(envelopes = setOf("TERM"), terminals = setOf("TERM"))), adv.canAdvancePastSyncFailure(msg148("TERM")))
        // String.lowercase() specials, pinned (Micro148.kt verified).
        // "İ" lowercases to two units "i" + combining dot above.
        assertTrue(isPlaceholderOracle148("İ", listOf("i\u0307")))
        assertFalse(isPlaceholderOracle148("İ", emptyList()))
        assertTrue(isPlaceholderOracle148("ΟΣ", listOf("ος")))
        assertFalse(isPlaceholderOracle148("ΟΣ", listOf("οσ")))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(148001)
        repeat(1500) {
            val texts = randomTexts148(rnd)
            val gate = randomGate148(rnd)
            val message = randomMessage148(rnd)
            val status = ChatDetailDecryptStatus(gate, texts)
            assertEquals(failedTextForOracle148(message.type, texts), status.failedTextFor(message.type))
            assertEquals(
                isPlaceholderOracle148(message.content, listOf(texts.failed, texts.pending, texts.sessionMissing, texts.identityChanged, texts.groupFailed, texts.groupKeyMissing, texts.groupIdentityChanged, texts.groupNewer, failedTextForOracle148(message.type, texts))),
                DecryptPlaceholderPolicy.isPlaceholder(message.content, texts.failed, texts.pending, texts.sessionMissing, texts.identityChanged, texts.groupFailed, texts.groupKeyMissing, texts.groupIdentityChanged, texts.groupNewer, failedTextForOracle148(message.type, texts)),
            )
            assertEquals(isSyncFailurePlaceholderOracle148(message, gate, texts), status.isSyncFailurePlaceholder(message))
            assertEquals(canAdvancePastSyncFailureOracle148(message, gate), status.canAdvancePastSyncFailure(message))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every type through failedTextFor with the fixed texts.
        for (type in MessageType.entries) {
            val s = ChatDetailDecryptStatus(FakeGate148(), TEXTS148)
            assertEquals(failedTextForOracle148(type, TEXTS148), s.failedTextFor(type))
        }
        // Every pool content through isPlaceholder / isSync / canAdvance.
        val gate = FakeGate148(
            envelopes = setOf("ENV", "TERM", "EXH"),
            senderKeys = setOf("SK", "SK_TERM"),
            terminals = setOf("TERM", "SK_TERM"),
            exhausted = setOf("EXH"),
        )
        val s = ChatDetailDecryptStatus(gate, TEXTS148)
        for (content in CONTENT_POOL148) {
            val m = msg148(content)
            assertEquals(
                isPlaceholderOracle148(content, listOf(TEXTS148.failed, TEXTS148.pending, TEXTS148.sessionMissing, TEXTS148.identityChanged, TEXTS148.groupFailed, TEXTS148.groupKeyMissing, TEXTS148.groupIdentityChanged, TEXTS148.groupNewer, failedTextForOracle148(MessageType.TEXT, TEXTS148))),
                DecryptPlaceholderPolicy.isPlaceholder(content, TEXTS148.failed, TEXTS148.pending, TEXTS148.sessionMissing, TEXTS148.identityChanged, TEXTS148.groupFailed, TEXTS148.groupKeyMissing, TEXTS148.groupIdentityChanged, TEXTS148.groupNewer, failedTextForOracle148(MessageType.TEXT, TEXTS148)),
            )
            assertEquals(isSyncFailurePlaceholderOracle148(m, gate, TEXTS148), s.isSyncFailurePlaceholder(m))
            assertEquals(canAdvancePastSyncFailureOracle148(m, gate), s.canAdvancePastSyncFailure(m))
        }
        // Single-unit contents (incl. lowercase specials and whitespace).
        val units = (0..0x7F).toList() +
            listOf(0x80, 0x85, 0xA0, 0xFF, 0x100, 0x130, 0x149, 0x17F, 0x1F88, 0x1FBC, 0x1FFC, 0x3A3, 0x3C2, 0x3C3, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF, 0x2000, 0x2028, 0x2029, 0x3000)
        for (u in units) {
            val ch = String(charArrayOf(u.toChar()))
            val m = msg148(ch)
            assertEquals(isSyncFailurePlaceholderOracle148(m, FakeGate148(), TEXTS148), s.isSyncFailurePlaceholder(m))
            assertEquals(canAdvancePastSyncFailureOracle148(m, FakeGate148()), s.canAdvancePastSyncFailure(m))
        }
        // Gate-set size sweep.
        for (n in 0..4) {
            val env = setOf("ENV", "TERM", "EXH", "SK").take(n).toSet()
            val g = FakeGate148(envelopes = env, terminals = setOf("TERM"))
            val st = ChatDetailDecryptStatus(g, TEXTS148)
            for (content in listOf("ENV", "TERM", "EXH", "SK", "hello")) {
                val m = msg148(content)
                assertEquals(isSyncFailurePlaceholderOracle148(m, g, TEXTS148), st.isSyncFailurePlaceholder(m))
                assertEquals(canAdvancePastSyncFailureOracle148(m, g), st.canAdvancePastSyncFailure(m))
            }
        }
    }
}
