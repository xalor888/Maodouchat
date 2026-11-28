// Fuzz / random-oracle tests for ArrivalPreviewPolicy.preview
// (checklist line 931, 140th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: text, markdown
// and system messages show Message.parsedContent() (last <meta> cut,
// then ChatTextDisplayPolicy.unescapeHtmlEntities) truncated to 500
// units; every other type maps to a fixed variant, revoked and Sender
// Key distribution both show as Encrypted. No fuzz test covered
// ArrivalPreviewPolicy.preview before this block (content-grep dedup
// 0 hits on all remote refactor branches and main's FuzzTest files
// for ArrivalPreviewPolicy / ArrivalPreview; parsedContent and
// unescapeHtmlEntities have no FuzzTest coverage either). A hand-written
// ArrivalPreviewPolicyTest covers the same branches with fixed cases;
// this file adds the random oracle plus entity/meta sweeps.
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

private fun lastMetaIndexOracle(s: String): Int {
    val lit = "<meta>"
    var i = s.length - lit.length
    while (i >= 0) {
        var ok = true
        for (j in lit.indices) if (s[i + j] != lit[j]) { ok = false; break }
        if (ok) return i
        i--
    }
    return -1
}

private fun unescapeHtmlEntitiesOracle(raw: String): String {
    if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
    var out = raw
    out = Regex("&#(\\d{1,7});").replace(out) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    out = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE).replace(out) { match ->
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

private fun parsedContentOracle(content: String): String {
    val idx = lastMetaIndexOracle(content)
    val body = if (idx < 0) content else content.substring(0, idx)
    return unescapeHtmlEntitiesOracle(body)
}

private fun arrivalPreviewOracle(message: Message): ArrivalPreview = when (message.type) {
    MessageType.TEXT,
    MessageType.MARKDOWN,
    MessageType.SYSTEM -> ArrivalPreview.Text(parsedContentOracle(message.content).take(500))
    MessageType.IMAGE -> ArrivalPreview.Image
    MessageType.GIF -> ArrivalPreview.Gif
    MessageType.STICKER -> ArrivalPreview.Sticker
    MessageType.LOCATION -> ArrivalPreview.Location
    MessageType.VOICE -> ArrivalPreview.Voice
    MessageType.VIDEO -> ArrivalPreview.Video
    MessageType.FILE -> ArrivalPreview.File
    MessageType.NUDGE -> ArrivalPreview.Nudge
    MessageType.REVOKED,
    MessageType.SK_DIST -> ArrivalPreview.Encrypted
}

// ---------- generators (mirrored by Oracle140 GEN, seed 140003) ----------

private val AP_CONTENT_POOL = arrayOf(
    "", " ", "hello", "a &amp; b", "&lt;tag&gt;", "&#65;&#x42;", "&#x1F600;",
    "&#0;", "&#1114112;", "&#55296;", "&nbsp;x", "&amp;lt;",
    "&#39;&apos;&quot;", "&unknown;", "5 &gt; 3", "<meta>&amp;",
    "pre<meta>post", "hello<meta>{}", "a<meta>b<meta>c", "a<meta",
    "line1\nline2", "😀昵称", "a\uD800b"
)

private val AP_ENTITY_FRAGS = arrayOf(
    "&amp;", "&lt;", "&#65;", "&#x41;", "<meta>", "a", " ", "\n", "&gt;", "&#x1F600;"
)

private fun genApContent(rnd: Random): String = when (rnd.nextInt(4)) {
    0 -> AP_CONTENT_POOL[rnd.nextInt(AP_CONTENT_POOL.size)]
    1 -> "x".repeat(rnd.nextInt(0, 601))
    2 -> randomText(rnd, 12)
    else -> {
        val sb = StringBuilder()
        repeat(rnd.nextInt(0, 6)) { sb.append(AP_ENTITY_FRAGS[rnd.nextInt(AP_ENTITY_FRAGS.size)]) }
        sb.toString()
    }
}

private data class ApCase(
    val type: MessageType,
    val content: String,
)

private fun genApCase(rnd: Random): ApCase {
    val type = MessageType.entries[rnd.nextInt(MessageType.entries.size)]
    val content = genApContent(rnd)
    return ApCase(type, content)
}

private fun apMessage(c: ApCase): Message =
    Message(
        id = "m1", chatId = "c1", senderId = "u1",
        content = c.content, type = c.type, timestamp = 0,
    )

class ArrivalPreviewPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Every type maps to its own variant; text kinds show the body.
        val byType = mapOf(
            MessageType.TEXT to ArrivalPreview.Text("body"),
            MessageType.MARKDOWN to ArrivalPreview.Text("body"),
            MessageType.SYSTEM to ArrivalPreview.Text("body"),
            MessageType.IMAGE to ArrivalPreview.Image,
            MessageType.GIF to ArrivalPreview.Gif,
            MessageType.STICKER to ArrivalPreview.Sticker,
            MessageType.LOCATION to ArrivalPreview.Location,
            MessageType.VOICE to ArrivalPreview.Voice,
            MessageType.VIDEO to ArrivalPreview.Video,
            MessageType.FILE to ArrivalPreview.File,
            MessageType.NUDGE to ArrivalPreview.Nudge,
            MessageType.REVOKED to ArrivalPreview.Encrypted,
            MessageType.SK_DIST to ArrivalPreview.Encrypted,
        )
        for ((type, want) in byType) {
            val msg = Message(
                id = "m1", chatId = "c1", senderId = "u1",
                content = "body", type = type, timestamp = 0,
            )
            assertEquals(want, ArrivalPreviewPolicy.preview(msg))
        }
        val text = Message(
            id = "m1", chatId = "c1", senderId = "u1",
            content = "hello", type = MessageType.TEXT, timestamp = 0,
        )
        // Long bodies truncate to 500 units; the 500-unit body is kept whole.
        assertEquals(
            ArrivalPreview.Text("x".repeat(500)),
            ArrivalPreviewPolicy.preview(text.copy(content = "x".repeat(600)))
        )
        assertEquals(
            ArrivalPreview.Text("x".repeat(500)),
            ArrivalPreviewPolicy.preview(text.copy(content = "x".repeat(500)))
        )
        // Entities decode once, in production order: numeric first, then
        // the named ones, so &amp;lt; ends as the literal &lt;.
        assertEquals(
            ArrivalPreview.Text("a & b"),
            ArrivalPreviewPolicy.preview(text.copy(content = "a &amp; b"))
        )
        assertEquals(
            ArrivalPreview.Text("AB"),
            ArrivalPreviewPolicy.preview(text.copy(content = "&#65;&#x42;"))
        )
        assertEquals(
            ArrivalPreview.Text("<tag>"),
            ArrivalPreviewPolicy.preview(text.copy(content = "&lt;tag&gt;"))
        )
        assertEquals(
            ArrivalPreview.Text("&lt;"),
            ArrivalPreviewPolicy.preview(text.copy(content = "&amp;lt;"))
        )
        // Out-of-range numeric entities stay literal; a surrogate code
        // point becomes a lone surrogate unit.
        assertEquals(
            ArrivalPreview.Text("&#0;"),
            ArrivalPreviewPolicy.preview(text.copy(content = "&#0;"))
        )
        assertEquals(
            ArrivalPreview.Text("&#1114112;"),
            ArrivalPreviewPolicy.preview(text.copy(content = "&#1114112;"))
        )
        assertEquals(
            ArrivalPreview.Text("\uD800"),
            ArrivalPreviewPolicy.preview(text.copy(content = "&#55296;"))
        )
        // The meta block is cut at the last <meta>; entities before the
        // cut still decode.
        assertEquals(
            ArrivalPreview.Text("hello"),
            ArrivalPreviewPolicy.preview(text.copy(content = "hello<meta>{}"))
        )
        assertEquals(
            ArrivalPreview.Text("a<meta>b"),
            ArrivalPreviewPolicy.preview(text.copy(content = "a<meta>b<meta>c"))
        )
        assertEquals(
            ArrivalPreview.Text("a &"),
            ArrivalPreviewPolicy.preview(text.copy(content = "a &amp;<meta>post"))
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle140.genAp consumption order exactly, so seed
        // 140003 replays the same 1500 GEN lines drive140.py ran.
        val rnd = Random(140003)
        repeat(1500) {
            val c = genApCase(rnd)
            val msg = apMessage(c)
            assertEquals(arrivalPreviewOracle(msg), ArrivalPreviewPolicy.preview(msg))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every type x every pool content.
        for (type in MessageType.entries) {
            for (content in AP_CONTENT_POOL) {
                val msg = Message(
                    id = "m1", chatId = "c1", senderId = "u1",
                    content = content, type = type, timestamp = 0,
                )
                assertEquals(arrivalPreviewOracle(msg), ArrivalPreviewPolicy.preview(msg))
            }
        }
        // TEXT x-repeat lengths across the 500-unit truncation edge.
        for (n in listOf(0, 1, 498, 499, 500, 501, 502, 600)) {
            val msg = Message(
                id = "m1", chatId = "c1", senderId = "u1",
                content = "x".repeat(n), type = MessageType.TEXT, timestamp = 0,
            )
            assertEquals(arrivalPreviewOracle(msg), ArrivalPreviewPolicy.preview(msg))
        }
        // TEXT with every single low unit as a 1-char content.
        for (code in 0x00..0x7F) {
            val msg = Message(
                id = "m1", chatId = "c1", senderId = "u1",
                content = code.toChar().toString(), type = MessageType.TEXT, timestamp = 0,
            )
            assertEquals(arrivalPreviewOracle(msg), ArrivalPreviewPolicy.preview(msg))
        }
    }
}
