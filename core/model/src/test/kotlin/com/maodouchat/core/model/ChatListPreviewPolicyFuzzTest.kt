package com.maodouchat.core.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ChatListPreviewPolicy] (checklist line 931, 31st block).
 * Test-only, zero production changes. The oracle below re-implements the policy
 * from its specification; a Python mini-model differentially checks the oracle
 * transcription against an independent production transcription.
 */
class ChatListPreviewPolicyFuzzTest {

    private val mediaLabel: (MessageType) -> String = { type ->
        when (type) {
            MessageType.IMAGE -> "[图片]"
            MessageType.GIF -> "[GIF]"
            MessageType.STICKER -> "[贴纸]"
            MessageType.LOCATION -> "[位置]"
            MessageType.VOICE -> "[语音]"
            MessageType.VIDEO -> "[视频]"
            MessageType.FILE -> "[文件]"
            else -> "[?]"
        }
    }

    private val rewriter: (Message) -> String = { "改写:${it.content}" }

    private fun sample(type: MessageType, content: String, ts: Long, id: String = "m1") = Message(
        id = id,
        chatId = "c1",
        senderId = "u1",
        content = content,
        type = type,
        timestamp = ts,
        status = MessageStatus.SENT
    )

    // ---- oracle (independent re-implementation of the policy) ----

    private val numericDecimal = Regex("&#(\\d{1,7});")
    private val numericHex = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)

    private fun oracleUnescape(raw: String): String {
        if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
        var out = raw
        out = numericDecimal.replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }
                ?.let { String(intArrayOf(it), 0, 1) } ?: m.value
        }
        out = numericHex.replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }
                ?.let { String(intArrayOf(it), 0, 1) } ?: m.value
        }
        return out
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private fun oracleLooksLikeLocalMediaUri(content: String): Boolean {
        val t = content.trim()
        return t.startsWith("file:") || t.startsWith("content:") ||
            t.startsWith("maodou-attachment://")
    }

    private fun oracleIsSignalWireEnvelope(content: String): Boolean {
        val t = content.trimStart()
        if (!t.startsWith("{") && !t.startsWith("[{")) return false
        if (t.contains("signal-")) {
            return t.contains("\"algorithm\"") || t.contains("\"distributionMessage\"") ||
                t.contains("\"ciphertext\"") || t.contains("\"senderKey")
        }
        val hasCiphertext = t.contains("\"ciphertext\"")
        val hasSenderDevice = t.contains("\"senderDeviceId\"")
        val hasEntries = t.contains("\"entries\"")
        val hasCiphertextType = t.contains("\"ciphertextType\"")
        val hasDistribution = t.contains("\"distributionMessage\"")
        return hasDistribution ||
            (hasCiphertext && (hasSenderDevice || hasEntries || hasCiphertextType))
    }

    private fun oracleLooksLikeGarbage(content: String): Boolean {
        val t = content.trim()
        if (t.isBlank()) return true
        if (oracleLooksLikeLocalMediaUri(t)) return true
        if (oracleIsSignalWireEnvelope(t)) return true
        if (t.startsWith("eyJ") && t.length >= 40) return true
        if (t.contains("<meta>", ignoreCase = true) || t.contains("</meta>", ignoreCase = true)) return true
        // Placeholder set is data, not logic: reuse the production lookup here;
        // the Python mini-model transcribes the set independently.
        if (DecryptPlaceholderPolicy.isPlaceholder(t)) return true
        if (oracleIsUrlOnly(t) && oracleLooksLikeLeftoverUrl(t)) return true
        return false
    }

    private fun oracleIsUrlOnly(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.contains(' ') || t.contains('\n')) return false
        return t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) ||
            t.startsWith("www.", ignoreCase = true)
    }

    private fun oracleLooksLikeLeftoverUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/api/attachments") ||
            lower.contains("maodou-attachment") ||
            lower.contains("ciphertext") ||
            lower.contains("senderdeviceid")
    }

    private fun oracleVisiblePreviewText(raw: String): String {
        val idx = raw.lastIndexOf("<meta>")
        val withoutMeta = (if (idx < 0) raw else raw.substring(0, idx)).trim()
        if (withoutMeta.isBlank()) return ""
        if (oracleLooksLikeLocalMediaUri(withoutMeta)) return ""
        return oracleUnescape(withoutMeta)
    }

    private fun oracleListVisibleText(raw: String, encryptedPlaceholder: String): String {
        val body = oracleVisiblePreviewText(raw)
        if (body.isBlank() || oracleLooksLikeGarbage(body)) return encryptedPlaceholder
        return body
    }

    private fun oracleIsUnreadable(message: Message): Boolean {
        if (message.type == MessageType.SK_DIST) return true
        return when (message.type) {
            MessageType.TEXT, MessageType.MARKDOWN ->
                oracleLooksLikeGarbage(oracleVisiblePreviewText(message.content).ifBlank { message.content })
            else -> false
        }
    }

    private fun oracleFromLatestMessage(
        latest: Message?,
        nudgeText: ((Message) -> String)? = null
    ): ChatListPreview {
        if (latest == null || latest.type == MessageType.SK_DIST) {
            return ChatListPreview(text = "", type = MessageType.TEXT, timestamp = 0L)
        }
        val text = when (latest.type) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.LOCATION,
            MessageType.VOICE,
            MessageType.VIDEO,
            MessageType.FILE -> mediaLabel(latest.type)
            MessageType.NUDGE -> nudgeText?.invoke(latest) ?: latest.content
            MessageType.REVOKED -> "[revoked]"
            MessageType.SYSTEM -> latest.content
            MessageType.SK_DIST -> ""
            MessageType.TEXT, MessageType.MARKDOWN ->
                oracleListVisibleText(latest.content, "[enc]")
        }
        return ChatListPreview(text = text, type = latest.type, timestamp = latest.timestamp)
    }

    private fun oracleFromLatestMessages(
        candidatesNewestFirst: List<Message>,
        nudgeText: ((Message) -> String)? = null
    ): ChatListPreview {
        val head = candidatesNewestFirst.firstOrNull { candidate ->
            candidate.type != MessageType.SK_DIST && !oracleIsUnreadable(candidate)
        }
        return oracleFromLatestMessage(head, nudgeText)
    }

    private fun oracleOwnEcho(
        messageType: MessageType,
        sameMessagePlainOrLabel: String,
        existingListPreview: String?,
        maxLen: Int = 200
    ): String {
        return when (messageType) {
            MessageType.TEXT, MessageType.MARKDOWN -> sameMessagePlainOrLabel.take(maxLen)
            MessageType.NUDGE -> sameMessagePlainOrLabel.takeIf { it.isNotBlank() }
                ?: existingListPreview?.takeIf { it.isNotBlank() }
                ?: sameMessagePlainOrLabel
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.LOCATION,
            MessageType.VOICE,
            MessageType.VIDEO,
            MessageType.FILE -> mediaLabel(messageType)
            else -> sameMessagePlainOrLabel.take(maxLen)
        }
    }

    // ---- random input generation ----

    private val fragments = listOf(
        "hello", "你好世界", "see https://example.com/notes", "a b c", "12345",
        "  padded  ", "", " ", "\t\n", "😀你好",
        "hello<meta>{\"attachmentId\":\"att_abc\"}</meta>",
        "<meta>{}</meta>", "a<meta>b<meta>c",
        "<META>upper</META>", "text <meta>tail",
        "file:///data/cache/img.jpg", "content://media/1",
        "maodou-attachment://att_1", "  file://x  ", "FILE:///upper",
        "see file://mid",
        "eyJ" + "A".repeat(37), "eyJ" + "A".repeat(36),
        "eyJ" + "B".repeat(60), "eyJshort",
        """{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"ciphertextType":"prekey","ciphertext":"NAgB"}]}""",
        """{"version":3,"algorithm":"signal-multi-device-v1","ciphertext":"abc"}""",
        """{"type":"ciphertext","body":"..."}""",
        """{"ciphertext":"x"}""", """{"senderDeviceId":1}""",
        """{"ciphertext":"x","entries":[]}""",
        """signal-{"algorithm":"x"}""",
        """[{"ciphertext":"x","senderDeviceId":1}]""",
        """{"signal-version":1,"algorithm":"x"}""",
        "无法解密", "[无法解密的消息]", "decrypt failed", "Decrypt Failed",
        "session missing", " 无法解密 ", "这个怎么解密？",
        "[加密消息]", "[unable to decrypt file]", "无法解密。", "decrypt failed!",
        "&amp;", "&#65;", "&#x41;", "&#x4f60;", "&nbsp;", "&lt;tag&gt;",
        "&amp;amp;", "&#0;", "&#1114112;", "&#39;", "Tom &amp; Jerry",
        "&#xZZ;", "&#12345678;",
        "https://host/api/attachments/att_abc", "https://host/share?id=abc",
        "https://host/ciphertext/x", "https://host/senderdeviceid/x",
        "https://www.yhfx.jwznb.com/share?id=abc123",
        "www.example.com/maodou-attachment/x",
        "http://h/api/attachments/a", "https://host/other"
    )

    private fun randomContent(r: Random): String {
        val n = r.nextInt(1, 4)
        val sep = if (r.nextBoolean()) "" else " "
        return (1..n).joinToString(sep) { fragments[r.nextInt(fragments.size)] }
    }

    private val timestamps = listOf(0L, 1L, 9L, 42L, 100L, 1000L, Long.MAX_VALUE)

    private fun randomMessage(r: Random, index: Int): Message {
        val type = MessageType.entries[r.nextInt(MessageType.entries.size)]
        return sample(type, randomContent(r), timestamps[r.nextInt(timestamps.size)], id = "m$index")
    }

    // ---- tests ----

    @Test
    fun randomHeadSelectionMatchesOracle() {
        val r = Random(3101)
        repeat(300) {
            val size = r.nextInt(0, 7)
            val candidates = (0 until size).map { i -> randomMessage(r, i) }
            val expected = oracleFromLatestMessages(candidates, rewriter)
            val actual = ChatListPreviewPolicy.fromLatestMessages(
                candidates, mediaLabel, "[enc]", "[revoked]", rewriter
            )
            assertEquals(expected, actual, "candidates=$candidates")
            // Determinism: same input, same output.
            val again = ChatListPreviewPolicy.fromLatestMessages(
                candidates, mediaLabel, "[enc]", "[revoked]", rewriter
            )
            assertEquals(actual, again)
        }
    }

    @Test
    fun randomSingleMessageMatchesOracle() {
        val r = Random(3102)
        for (type in MessageType.entries) {
            repeat(20) {
                val msg = sample(type, randomContent(r), timestamps[r.nextInt(timestamps.size)])
                assertEquals(
                    oracleFromLatestMessage(msg, rewriter),
                    ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, "[enc]", "[revoked]", rewriter)
                )
                assertEquals(
                    oracleFromLatestMessage(msg, null),
                    ChatListPreviewPolicy.fromLatestMessage(msg, mediaLabel, "[enc]", "[revoked]", null)
                )
            }
        }
    }

    @Test
    fun randomAffectsListHeadMatchesEqualityRule() {
        val r = Random(3103)
        val ids: List<String?> = listOf(null, "", " ", "m1", "m2", " m1 ", "m1 ")
        repeat(300) {
            val head = ids[r.nextInt(ids.size)]
            val mutated = listOf("", " ", "m1", "m2", " m1 ").let { it[r.nextInt(it.size)] }
            val expected = if (mutated.isBlank()) {
                false
            } else if (head.isNullOrBlank()) {
                true
            } else {
                head == mutated
            }
            assertEquals(
                expected,
                ChatListPreviewPolicy.affectsListHead(head, mutated),
                "head=$head mutated=$mutated"
            )
        }
    }

    @Test
    fun randomUnreadableMatchesOracle() {
        val r = Random(3104)
        repeat(300) {
            val msg = randomMessage(r, 0)
            assertEquals(oracleIsUnreadable(msg), ChatListPreviewPolicy.isUnreadableListHead(msg))
            assertEquals(
                msg.type == MessageType.SK_DIST,
                ChatListPreviewPolicy.isListPreviewNoise(msg.type)
            )
        }
    }

    @Test
    fun listVisibleTextIsEitherPlaceholderOrCleanBody() {
        val r = Random(3105)
        repeat(300) {
            val raw = randomContent(r)
            val result = ChatListPreviewPolicy.listVisibleText(raw, "[enc]")
            if (result != "[enc]") {
                assertEquals(ChatListPreviewPolicy.visiblePreviewText(raw), result)
                assertTrue(result.isNotBlank())
                assertFalse(ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage(result))
            }
            assertEquals(
                result,
                ChatListPreviewPolicy.listVisibleText(raw, "[enc]"),
                "not deterministic for raw=$raw"
            )
        }
    }

    @Test
    fun randomOwnEchoMatchesOracle() {
        val r = Random(3106)
        val plains = listOf("", " ", "hello", "拍了拍", "a".repeat(250), "😀".repeat(120))
        val maxLens = listOf(0, 1, 5, 200, 201, 1000)
        repeat(300) {
            val type = MessageType.entries[r.nextInt(MessageType.entries.size)]
            val plain = plains[r.nextInt(plains.size)]
            val existing = listOf<String?>(null, "", " ", "old tail").let { it[r.nextInt(it.size)] }
            val maxLen = maxLens[r.nextInt(maxLens.size)]
            assertEquals(
                oracleOwnEcho(type, plain, existing, maxLen),
                ChatListPreviewPolicy.ownEchoListPreview(type, plain, existing, mediaLabel, maxLen)
            )
        }
    }

    @Test
    fun boundaryPins() {
        // eyJ threshold is exactly 40 chars.
        assertTrue(ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage("eyJ" + "A".repeat(37)))
        assertFalse(ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage("eyJ" + "A".repeat(36)))
        // <meta> mid-string is cut at the LAST occurrence; a surviving earlier one is garbage.
        assertEquals("a<meta>b", ChatListPreviewPolicy.visiblePreviewText("a<meta>b<meta>c"))
        assertEquals("[enc]", ChatListPreviewPolicy.listVisibleText("a<meta>b<meta>c", "[enc]"))
        assertEquals("hello", ChatListPreviewPolicy.visiblePreviewText("hello<meta>{}</meta>"))
        // Case-insensitive <meta> is garbage but only exact "<meta>" is stripped.
        assertEquals("[enc]", ChatListPreviewPolicy.listVisibleText("a<META>b", "[enc]"))
        // URL-only rule needs the whole trimmed body to be one URL.
        assertTrue(ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage("https://h/api/attachments/a"))
        assertFalse(ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage("see https://h/api/attachments/a"))
        assertFalse(ChatListPreviewPolicy.looksLikeLeftoverPreviewGarbage("https://host/share?id=abc"))
        // Signal envelope: "signal-" alone is not enough; quoted keys decide.
        assertFalse(ChatListPreviewPolicy.isSignalWireEnvelope("""{"ciphertext":"x"}"""))
        assertFalse(ChatListPreviewPolicy.isSignalWireEnvelope("""{"senderDeviceId":1}"""))
        assertTrue(ChatListPreviewPolicy.isSignalWireEnvelope("""{"ciphertext":"x","entries":[]}"""))
        assertFalse(ChatListPreviewPolicy.isSignalWireEnvelope("""signal-{"algorithm":"x"}"""))
        assertTrue(ChatListPreviewPolicy.isSignalWireEnvelope("""{"signal-version":1,"algorithm":"x"}"""))
        assertTrue(ChatListPreviewPolicy.isSignalWireEnvelope("""[{"ciphertext":"x","senderDeviceId":1}]"""))
        // Unescape: out-of-range numeric entities stay literal; double-encoded &amp; unwinds once.
        assertEquals("&#0;", ChatListPreviewPolicy.visiblePreviewText("&#0;"))
        assertEquals("&#1114112;", ChatListPreviewPolicy.visiblePreviewText("&#1114112;"))
        assertEquals("&amp;", ChatListPreviewPolicy.visiblePreviewText("&amp;amp;"))
        assertEquals("A", ChatListPreviewPolicy.visiblePreviewText("&#65;"))
        assertEquals("你", ChatListPreviewPolicy.visiblePreviewText("&#x4f60;"))
        // Placeholder matching is whole-string after trim + lowercase.
        assertTrue(ChatListPreviewPolicy.looksLikeDecryptFailurePlaceholder(" DECRYPT FAILED "))
        assertFalse(ChatListPreviewPolicy.looksLikeDecryptFailurePlaceholder("无法解密。"))
        // Remote/local URI schemes are case-sensitive; leading blanks are trimmed first.
        assertFalse(ChatListPreviewPolicy.looksLikeLocalMediaUri("FILE:///x"))
        assertTrue(ChatListPreviewPolicy.looksLikeLocalMediaUri("  file://x  "))
        assertFalse(isRemoteAttachmentUri("  maodou-attachment://x"))
        assertTrue(isRemoteAttachmentUri("maodou-attachment://x"))
        // Empty TEXT is an unreadable head (skipped by fromLatestMessages) but
        // fromLatestMessage on its own still maps it to the placeholder.
        val empty = sample(MessageType.TEXT, "", 9L)
        assertTrue(ChatListPreviewPolicy.isUnreadableListHead(empty))
        assertEquals(
            "[enc]",
            ChatListPreviewPolicy.fromLatestMessage(empty, mediaLabel, "[enc]", "[revoked]").text
        )
        assertEquals(
            ChatListPreview("", MessageType.TEXT, 0L),
            ChatListPreviewPolicy.fromLatestMessages(listOf(empty), mediaLabel, "[enc]", "[revoked]")
        )
        // SK_DIST via fromLatestMessage is the blank preview, never the "" arm.
        val sk = sample(MessageType.SK_DIST, "dist", 9L)
        assertEquals(
            ChatListPreview("", MessageType.TEXT, 0L),
            ChatListPreviewPolicy.fromLatestMessage(sk, mediaLabel, "[enc]", "[revoked]")
        )
    }
}
