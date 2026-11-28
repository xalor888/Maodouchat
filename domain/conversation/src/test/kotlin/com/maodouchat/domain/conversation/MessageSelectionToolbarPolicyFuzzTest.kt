// MessageSelectionToolbarPolicy fuzz (149th block). Oracle reimplements
// resolveMessageSelectionToolbar / selectableMessageIds from production
// text, including the already fuzz-covered delegates it calls
// (isMessageForwardable / isMessageCopyable in fuzz-119,
// MessagePinPolicy.canPin in fuzz-46). This block covers the toolbar
// composition itself, not the delegates. Note the two block flags are
// dead inputs in production: forwardBlockEnabled / copyBlockEnabled
// never change the outcome (secretBlocks* only reads isSecretChat).
package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------- oracle ----------

private val PINNABLE_TYPES149 = setOf(
    MessageType.TEXT, MessageType.MARKDOWN, MessageType.IMAGE, MessageType.GIF,
    MessageType.STICKER, MessageType.LOCATION, MessageType.VOICE,
    MessageType.VIDEO, MessageType.FILE,
)

private fun isForwardableOracle149(type: MessageType, isSecretChat: Boolean): Boolean =
    !isSecretChat && type !in setOf(
        MessageType.NUDGE, MessageType.SK_DIST, MessageType.SYSTEM, MessageType.REVOKED,
    )

private fun isCopyableOracle149(type: MessageType, isSecretChat: Boolean): Boolean =
    !isSecretChat && type in setOf(MessageType.TEXT, MessageType.MARKDOWN)

private fun canPinOracle149(isGroup: Boolean, myRole: String?, type: MessageType): Boolean {
    if (type !in PINNABLE_TYPES149) return false
    if (!isGroup) return true
    val role = myRole?.uppercase().orEmpty()
    return role == "OWNER" || role == "ADMIN"
}

private val DECIMAL_ENTITY149 = Regex("&#([0-9]{1,7});")
private val HEX_ENTITY149 = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)

private fun unescapeHtmlEntitiesOracle149(raw: String): String {
    if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
    var out = raw
    out = DECIMAL_ENTITY149.replace(out) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
            // Micro149: lone surrogates pass through as one unit, no throw.
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    out = HEX_ENTITY149.replace(out) { match ->
        match.groupValues[1].toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
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

private fun parsedContentOracle149(content: String): String {
    val idx = content.lastIndexOf("<meta>")
    val body = if (idx < 0) content else content.substring(0, idx)
    return unescapeHtmlEntitiesOracle149(body)
}

private fun resolveOracle149(
    selectedMessages: List<Message>,
    chatIsGroup: Boolean,
    myMemberRole: String?,
    pinnedMessageIds: Set<String>,
    isSecretChat: Boolean,
    forwardBlockEnabled: Boolean,
    copyBlockEnabled: Boolean,
): MessageSelectionToolbarState {
    val forwardableMessages = selectedMessages.filter {
        isForwardableOracle149(it.type, isSecretChat)
    }
    val shouldStar = selectedMessages.any { !it.starred }
    val shouldPin = selectedMessages.any { it.id !in pinnedMessageIds }
    val canBatchPin = selectedMessages.any {
        canPinOracle149(chatIsGroup, myMemberRole, it.type)
    }
    val copyable = selectedMessages
        .filter { isCopyableOracle149(it.type, isSecretChat) }
        .map { parsedContentOracle149(it.content) }
        .filter { it.isNotBlank() }
    return MessageSelectionToolbarState(
        forwardableMessages = forwardableMessages,
        shouldStar = shouldStar,
        shouldPin = shouldPin,
        canBatchPin = canBatchPin,
        copyText = copyable.joinToString("\n").takeIf { it.isNotEmpty() },
    )
}

private fun selectableOracle149(
    allMessages: List<Message>,
    preparingAttachmentMessageIds: Set<String>,
): Set<String> = allMessages
    .filter { it.type != MessageType.SYSTEM && it.type != MessageType.SK_DIST }
    .filter {
        it.status != MessageStatus.SENDING &&
            it.id !in preparingAttachmentMessageIds
    }
    .map { it.id }
    .toSet()

// ---------- generators ----------

private val CONTENT_POOL149 = arrayOf(
    "", " ", "  ", "hello", "Hello World", "晚上一起吃饭吗",
    "a &amp; b", "a &lt; b &gt; c", "&quot;hi&quot;", "it&#39;s", "it&apos;s",
    "&nbsp;pad&nbsp;", "&#65;&#66;&#67;", "&#x41;&#x42;", "&#X4a;&#X4B;",
    "&#38;#39;", "&amp;lt;", "&amp;amp;", "&amp;nbsp;", "&#38;#x41;",
    "&#0;", "&#1114112;", "&#9999999;", "&#x110000;", "&#xFFFFFF;",
    "&#55296;", "&#xD800;", "&#xDFFF;", "&#xE000;", "&#x10FFFF;",
    "&#;", "&#x;", "&#12", "&#x4", "&unknown;", "&;", "&",
    "text<meta>{\"a\":1}</meta>", "text<meta>{\"a\":1}",
    "a<meta>b</meta>c<meta>d</meta>", "<meta>", "pre <meta> tail",
    "&#65;<meta>&#66;", "İ", "ß", "ﬀ", "ŉ", "ＡＢＣ",
)

private val ROLE_POOL149 = arrayOf<String?>(
    null, "", " ", "OWNER", "ADMIN", "owner", "admin", "Admin", "oWnEr",
    "MEMBER", "member", " OWNER", "OWNER ", "ADMINISTRATOR", "own",
    "ı", "i", "İ", "ß", "ﬀ", "ŉ", "ᴏᴡɴᴇʀ",
)

private fun randomText149(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) {
        when (rnd.nextInt(10)) {
            0 -> sb.append(('a'.code + rnd.nextInt(26)).toChar())
            1 -> sb.append(('0'.code + rnd.nextInt(10)).toChar())
            2 -> sb.append(' ')
            3 -> sb.append(rnd.nextInt(0x20, 0x2FFF).toChar())
            else -> sb.append(CONTENT_POOL149[rnd.nextInt(CONTENT_POOL149.size)])
        }
    }
    return sb.toString()
}

private fun randomContent149(rnd: Random): String =
    if (rnd.nextInt(2) == 0) CONTENT_POOL149[rnd.nextInt(CONTENT_POOL149.size)]
    else randomText149(rnd, 12)

private fun randomMessage149(rnd: Random): Message {
    val types = MessageType.entries
    val statuses = MessageStatus.entries
    return Message(
        id = "m" + rnd.nextInt(0, 5),
        chatId = "c" + rnd.nextInt(0, 3),
        senderId = "u" + rnd.nextInt(0, 3),
        content = randomContent149(rnd),
        type = types[rnd.nextInt(types.size)],
        timestamp = rnd.nextLong(),
        status = statuses[rnd.nextInt(statuses.size)],
        starred = rnd.nextBoolean(),
    )
}

private fun randomMessages149(rnd: Random): List<Message> {
    val n = rnd.nextInt(0, 5)
    return (0 until n).map { randomMessage149(rnd) }
}

private fun randomIdSet149(rnd: Random): Set<String> {
    val n = rnd.nextInt(0, 4)
    return (0 until n).map { "m" + rnd.nextInt(0, 5) }.toSet()
}

class MessageSelectionToolbarPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Forward/copy per type: secret blocks both, flags are dead inputs.
        for (type in MessageType.entries) {
            val gestureOk = type !in setOf(
                MessageType.NUDGE, MessageType.SK_DIST,
                MessageType.SYSTEM, MessageType.REVOKED,
            )
            assertEquals(gestureOk, isForwardableOracle149(type, false))
            assertEquals(false, isForwardableOracle149(type, true))
            val copyOk = type in setOf(MessageType.TEXT, MessageType.MARKDOWN)
            assertEquals(copyOk, isCopyableOracle149(type, false))
            assertEquals(false, isCopyableOracle149(type, true))
        }
        // canPin: group needs OWNER/ADMIN after uppercase; direct allows all pinnable.
        assertTrue(canPinOracle149(true, "owner", MessageType.TEXT))
        assertTrue(canPinOracle149(true, "ADMIN", MessageType.IMAGE))
        assertTrue(canPinOracle149(true, "oWnEr", MessageType.FILE))
        assertEquals(false, canPinOracle149(true, "MEMBER", MessageType.TEXT))
        assertEquals(false, canPinOracle149(true, null, MessageType.TEXT))
        assertEquals(false, canPinOracle149(true, "", MessageType.TEXT))
        assertEquals(false, canPinOracle149(true, " OWNER", MessageType.TEXT))
        assertEquals(false, canPinOracle149(true, "OWNER", MessageType.NUDGE))
        assertEquals(false, canPinOracle149(true, "OWNER", MessageType.SYSTEM))
        assertTrue(canPinOracle149(false, "MEMBER", MessageType.TEXT))
        assertTrue(canPinOracle149(false, null, MessageType.STICKER))
        assertEquals(false, canPinOracle149(false, null, MessageType.REVOKED))
        // parsedContent: last <meta> wins, entities decode in fixed pass order.
        assertEquals("hello", parsedContentOracle149("hello"))
        assertEquals("text", parsedContentOracle149("text<meta>{\"a\":1}</meta>"))
        assertEquals("a<meta>b</meta>c", parsedContentOracle149("a<meta>b</meta>c<meta>d</meta>"))
        assertEquals("", parsedContentOracle149("<meta>"))
        assertEquals("a & b", parsedContentOracle149("a &amp; b"))
        assertEquals("a < b > c", parsedContentOracle149("a &lt; b &gt; c"))
        assertEquals("\"hi\"", parsedContentOracle149("&quot;hi&quot;"))
        assertEquals("it's", parsedContentOracle149("it&#39;s"))
        assertEquals("it's", parsedContentOracle149("it&apos;s"))
        assertEquals(" pad ", parsedContentOracle149("&nbsp;pad&nbsp;"))
        assertEquals("ABC", parsedContentOracle149("&#65;&#66;&#67;"))
        assertEquals("AB", parsedContentOracle149("&#x41;&#x42;"))
        assertEquals("JK", parsedContentOracle149("&#X4a;&#X4B;"))
        // Decimal pass creates &#39; which the literal pass then consumes.
        assertEquals("'", parsedContentOracle149("&#38;#39;"))
        // &amp; is replaced last, so &amp;lt; stops at &lt;.
        assertEquals("&lt;", parsedContentOracle149("&amp;lt;"))
        // &amp; is replaced last and only once per pass: &amp;amp; stops at &amp;.
        assertEquals("&amp;", parsedContentOracle149("&amp;amp;"))
        assertEquals("&nbsp;", parsedContentOracle149("&amp;nbsp;"))
        assertEquals("A", parsedContentOracle149("&#38;#x41;"))
        // Out-of-range / malformed entities stay literal.
        assertEquals("&#0;", parsedContentOracle149("&#0;"))
        assertEquals("&#1114112;", parsedContentOracle149("&#1114112;"))
        assertEquals("&#9999999;", parsedContentOracle149("&#9999999;"))
        assertEquals("&#x110000;", parsedContentOracle149("&#x110000;"))
        assertEquals("&#;", parsedContentOracle149("&#;"))
        assertEquals("&unknown;", parsedContentOracle149("&unknown;"))
        // Lone-surrogate entities pass through as one unit (Micro149).
        assertEquals("\uD800", parsedContentOracle149("&#55296;"))
        assertEquals("\uD800", parsedContentOracle149("&#xD800;"))
        assertEquals("\uDFFF", parsedContentOracle149("&#xDFFF;"))
        assertEquals("\uE000", parsedContentOracle149("&#xE000;"))
        assertEquals("\uDBFF\uDFFF", parsedContentOracle149("&#x10FFFF;"))
        // resolve: empty selection is all-false / null copyText.
        val empty = resolveMessageSelectionToolbar(
            emptyList(), false, null, emptySet(), false, false, false,
        )
        assertEquals(MessageSelectionToolbarState(emptyList(), false, false, false, null), empty)
        assertEquals(resolveOracle149(emptyList(), false, null, emptySet(), false, false, false), empty)
        // Dead flags: same inputs, either flag value, same output.
        val one = Message(id = "m1", chatId = "c1", senderId = "u1", content = "hi", type = MessageType.TEXT)
        val a = resolveMessageSelectionToolbar(listOf(one), false, null, emptySet(), true, true, true)
        val b = resolveMessageSelectionToolbar(listOf(one), false, null, emptySet(), true, false, false)
        assertEquals(a, b)
        assertEquals(emptyList(), a.forwardableMessages)
        assertNull(a.copyText)
        // copyText joins blank-filtered parsed contents with \n.
        val two = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u1", content = "a &amp; b", type = MessageType.TEXT),
            Message(id = "m2", chatId = "c1", senderId = "u1", content = "   ", type = MessageType.TEXT),
            Message(id = "m3", chatId = "c1", senderId = "u1", content = "c", type = MessageType.IMAGE),
        )
        val r = resolveMessageSelectionToolbar(two, false, null, emptySet(), false, false, false)
        assertEquals("a & b", r.copyText)
        // Forwardable filters by type only: the blank-content TEXT still forwards.
        assertEquals(listOf(two[0], two[1], two[2]), r.forwardableMessages)
        assertTrue(r.shouldStar)
        assertTrue(r.shouldPin)
        assertTrue(r.canBatchPin)
        assertEquals(resolveOracle149(two, false, null, emptySet(), false, false, false), r)
        // selectableMessageIds: SYSTEM/SK_DIST, SENDING and preparing ids drop out.
        val all = listOf(
            Message(id = "m1", chatId = "c1", senderId = "u1", content = "a", type = MessageType.TEXT, status = MessageStatus.SENT),
            Message(id = "m2", chatId = "c1", senderId = "u1", content = "a", type = MessageType.SYSTEM, status = MessageStatus.SENT),
            Message(id = "m3", chatId = "c1", senderId = "u1", content = "a", type = MessageType.SK_DIST, status = MessageStatus.SENT),
            Message(id = "m4", chatId = "c1", senderId = "u1", content = "a", type = MessageType.TEXT, status = MessageStatus.SENDING),
            Message(id = "m5", chatId = "c1", senderId = "u1", content = "a", type = MessageType.TEXT, status = MessageStatus.SENT),
        )
        assertEquals(setOf("m1", "m5"), selectableMessageIds(all, emptySet()))
        assertEquals(setOf("m1"), selectableMessageIds(all, setOf("m5")))
        assertEquals(selectableOracle149(all, setOf("m5")), selectableMessageIds(all, setOf("m5")))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(149001)
        repeat(1500) {
            val selected = randomMessages149(rnd)
            val chatIsGroup = rnd.nextBoolean()
            val role = ROLE_POOL149[rnd.nextInt(ROLE_POOL149.size)]
            val pinned = randomIdSet149(rnd)
            val isSecret = rnd.nextBoolean()
            val fwdBlock = rnd.nextBoolean()
            val copyBlock = rnd.nextBoolean()
            assertEquals(
                resolveOracle149(selected, chatIsGroup, role, pinned, isSecret, fwdBlock, copyBlock),
                resolveMessageSelectionToolbar(selected, chatIsGroup, role, pinned, isSecret, fwdBlock, copyBlock),
            )
            val all = randomMessages149(rnd)
            val preparing = randomIdSet149(rnd)
            assertEquals(selectableOracle149(all, preparing), selectableMessageIds(all, preparing))
            for (m in selected + all) {
                assertEquals(parsedContentOracle149(m.content), m.parsedContent())
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every type x status x starred through resolve with one message.
        for (type in MessageType.entries) {
            for (status in MessageStatus.entries) {
                for (starred in listOf(false, true)) {
                    for (secret in listOf(false, true)) {
                        for (group in listOf(false, true)) {
                            for (role in ROLE_POOL149) {
                                val m = Message(
                                    id = "m1", chatId = "c1", senderId = "u1",
                                    content = "x", type = type, status = status, starred = starred,
                                )
                                assertEquals(
                                    resolveOracle149(listOf(m), group, role, setOf("m1"), secret, true, true),
                                    resolveMessageSelectionToolbar(listOf(m), group, role, setOf("m1"), secret, true, true),
                                )
                                assertEquals(
                                    resolveOracle149(listOf(m), group, role, emptySet(), secret, false, false),
                                    resolveMessageSelectionToolbar(listOf(m), group, role, emptySet(), secret, false, false),
                                )
                            }
                        }
                    }
                }
            }
        }
        // Every pool content through parsedContent.
        for (content in CONTENT_POOL149) {
            assertEquals(parsedContentOracle149(content), Message(id = "m1", chatId = "c1", senderId = "u1", content = content).parsedContent())
        }
        // Single-unit contents (incl. entity syntax chars and lone surrogates).
        val units = (0..0x7F).toList() +
            listOf(0x80, 0x85, 0xA0, 0xFF, 0x100, 0x130, 0x149, 0x17F, 0x1F88, 0x3A3, 0x3C2, 0x7FF, 0x800, 0xFFF, 0xD800, 0xDFFF, 0xE000, 0xFFFE, 0xFFFF, 0x2000, 0x2028, 0x2029, 0x3000)
        for (u in units) {
            val ch = String(charArrayOf(u.toChar()))
            assertEquals(
                parsedContentOracle149(ch),
                Message(id = "m1", chatId = "c1", senderId = "u1", content = ch).parsedContent(),
            )
        }
        // Numeric entity code-point sweep, decimal and hex.
        val codes = listOf(0, 1, 9, 10, 31, 32, 34, 38, 39, 60, 62, 65, 97, 127, 128, 255, 0xD7FF, 0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0xE000, 0xFFFD, 0xFFFF, 0x10000, 0x10FFFF, 0x110000, 9999999)
        for (code in codes) {
            for (raw in listOf("&#$code;", "&#x${code.toString(16)};", "&#X${code.toString(16).uppercase()};")) {
                assertEquals(
                    parsedContentOracle149(raw),
                    Message(id = "m1", chatId = "c1", senderId = "u1", content = raw).parsedContent(),
                )
            }
        }
        // Role single-unit sweep through canPin (uppercase edge).
        for (u in units) {
            val role = String(charArrayOf(u.toChar()))
            assertEquals(canPinOracle149(true, role, MessageType.TEXT), MessagePinPolicy.canPin(true, role, MessageType.TEXT))
            assertEquals(canPinOracle149(false, role, MessageType.TEXT), MessagePinPolicy.canPin(false, role, MessageType.TEXT))
        }
        // selectableMessageIds: every type x status.
        for (type in MessageType.entries) {
            for (status in MessageStatus.entries) {
                val m = Message(id = "m1", chatId = "c1", senderId = "u1", content = "x", type = type, status = status)
                assertEquals(selectableOracle149(listOf(m), emptySet()), selectableMessageIds(listOf(m), emptySet()))
                assertEquals(selectableOracle149(listOf(m), setOf("m1")), selectableMessageIds(listOf(m), setOf("m1")))
            }
        }
    }
}
