// Fuzz / random-oracle tests for PinnedPreview.pinnedPreview
// (checklist line 931, 140th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: null falls back
// to Generic, otherwise MessagePinPolicy.previewKind maps the type and
// TEXT kinds wrap MessagePinPolicy.textPreview(content) (last <meta>
// cut, trim, newline fold, blank to "", truncate past 96 units).
// No fuzz test covered pinnedPreview before this block (content-grep
// dedup 0 hits on all remote refactor branches and main's FuzzTest
// files for PinnedPreview / pinnedPreview; the helpers previewKind
// and textPreview were already fuzz-covered in fuzz-46, the dispatch
// itself was not). A hand-written PinnedPreviewTest covers the same
// branches with fixed cases; this file adds the random oracle plus a
// full MessageType sweep.
package com.maodouchat.domain.conversation

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

// Kotlin trim()/isBlank() whitespace set, swept empirically on this
// JDK/stdlib (see fuzz-46): U+0009..U+000D, U+001C..U+0020, U+00A0,
// U+1680, U+2000..U+200A, U+2028..U+2029, U+202F, U+205F, U+3000.
private fun isWsOracle(c: Char): Boolean = when (c.code) {
    in 0x09..0x0D, in 0x1C..0x20 -> true
    0x00A0, 0x1680, 0x202F, 0x205F, 0x3000 -> true
    in 0x2000..0x200A, in 0x2028..0x2029 -> true
    else -> false
}

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

private fun trimOracle(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isWsOracle(s[start])) start++
    while (end > start && isWsOracle(s[end - 1])) end--
    return s.substring(start, end)
}

private fun isBlankOracle(s: String): Boolean {
    for (c in s) if (!isWsOracle(c)) return false
    return true
}

private fun foldNewlinesOracle(s: String): String {
    val out = StringBuilder(s.length)
    for (c in s) out.append(if (c == '\n') ' ' else c)
    return out.toString()
}

private fun textPreviewOracle(content: String, maxLen: Int = 96): String {
    val cut = lastMetaIndexOracle(content)
    val before = if (cut < 0) content else content.substring(0, cut)
    val plain = foldNewlinesOracle(trimOracle(before))
    if (isBlankOracle(plain)) return ""
    return if (plain.length <= maxLen) plain else plain.take(maxLen - 1) + "…"
}

private fun previewKindOracle(type: MessageType): PreviewKind = when (type) {
    MessageType.TEXT, MessageType.MARKDOWN -> PreviewKind.TEXT
    MessageType.IMAGE, MessageType.GIF -> PreviewKind.IMAGE
    MessageType.VOICE -> PreviewKind.VOICE
    MessageType.VIDEO -> PreviewKind.VIDEO
    MessageType.FILE -> PreviewKind.FILE
    MessageType.LOCATION -> PreviewKind.LOCATION
    MessageType.STICKER -> PreviewKind.STICKER
    else -> PreviewKind.GENERIC
}

private fun pinnedPreviewOracle(message: Message?): PinnedPreview {
    if (message == null) return PinnedPreview.Generic
    return when (previewKindOracle(message.type)) {
        PreviewKind.TEXT -> PinnedPreview.Text(textPreviewOracle(message.content))
        PreviewKind.IMAGE -> PinnedPreview.Image
        PreviewKind.VOICE -> PinnedPreview.Voice
        PreviewKind.VIDEO -> PinnedPreview.Video
        PreviewKind.FILE -> PinnedPreview.File
        PreviewKind.LOCATION -> PinnedPreview.Location
        PreviewKind.STICKER -> PinnedPreview.Sticker
        PreviewKind.GENERIC -> PinnedPreview.Generic
    }
}

// ---------- generators (mirrored by Oracle140 GEN, seed 140001) ----------

private val PP_CONTENT_POOL = arrayOf(
    "", " ", "hello", "a b", "line1\nline2", "<meta>{}", "hello<meta>{}",
    "a<meta>b<meta>c", "a<meta", "<meta>", "  spaced  ", "\u00A0",
    "😀", "a\uD800b", "\uD83D\uDE00", "tab\there", "multi\n\nlines"
)

private fun genPpContent(rnd: Random): String = when (rnd.nextInt(3)) {
    0 -> PP_CONTENT_POOL[rnd.nextInt(PP_CONTENT_POOL.size)]
    1 -> "x".repeat(rnd.nextInt(0, 131))
    else -> randomText(rnd, 12)
}

private data class PpCase(
    val type: MessageType,
    val content: String,
    val isNull: Boolean,
)

private fun genPpCase(rnd: Random): PpCase {
    val type = MessageType.entries[rnd.nextInt(MessageType.entries.size)]
    val content = genPpContent(rnd)
    val isNull = rnd.nextBoolean()
    return PpCase(type, content, isNull)
}

private fun ppMessage(c: PpCase): Message? =
    if (c.isNull) null else Message(
        id = "m1", chatId = "c1", senderId = "u1",
        content = c.content, type = c.type, timestamp = 0,
    )

class PinnedPreviewFuzzTest {

    @Test
    fun pinnedBoundaries() {
        assertEquals(PinnedPreview.Generic, pinnedPreview(null))
        // Every type with empty content maps to its own variant.
        val byType = mapOf(
            MessageType.TEXT to PinnedPreview.Text(""),
            MessageType.MARKDOWN to PinnedPreview.Text(""),
            MessageType.IMAGE to PinnedPreview.Image,
            MessageType.GIF to PinnedPreview.Image,
            MessageType.VOICE to PinnedPreview.Voice,
            MessageType.VIDEO to PinnedPreview.Video,
            MessageType.FILE to PinnedPreview.File,
            MessageType.LOCATION to PinnedPreview.Location,
            MessageType.STICKER to PinnedPreview.Sticker,
            MessageType.NUDGE to PinnedPreview.Generic,
            MessageType.SK_DIST to PinnedPreview.Generic,
            MessageType.SYSTEM to PinnedPreview.Generic,
            MessageType.REVOKED to PinnedPreview.Generic,
        )
        for ((type, want) in byType) {
            val msg = Message(
                id = "m1", chatId = "c1", senderId = "u1",
                content = "", type = type, timestamp = 0,
            )
            assertEquals(want, pinnedPreview(msg))
        }
        // Text keeps its content; blank stays blank; meta block is cut
        // at the last <meta>; newlines fold to spaces; past 96 units
        // the text is cut to 95 plus an ellipsis.
        val text = Message(
            id = "m1", chatId = "c1", senderId = "u1",
            content = "hello", type = MessageType.TEXT, timestamp = 0,
        )
        assertEquals(PinnedPreview.Text("hello"), pinnedPreview(text))
        assertEquals(
            PinnedPreview.Text(""),
            pinnedPreview(text.copy(content = "   "))
        )
        assertEquals(
            PinnedPreview.Text("hello"),
            pinnedPreview(text.copy(content = "hello<meta>{}"))
        )
        assertEquals(
            PinnedPreview.Text("a<meta>b"),
            pinnedPreview(text.copy(content = "a<meta>b<meta>c"))
        )
        assertEquals(
            PinnedPreview.Text("a b"),
            pinnedPreview(text.copy(content = "a\nb"))
        )
        assertEquals(
            PinnedPreview.Text("x".repeat(95) + "…"),
            pinnedPreview(text.copy(content = "x".repeat(200)))
        )
        assertEquals(
            PinnedPreview.Text("x".repeat(96)),
            pinnedPreview(text.copy(content = "x".repeat(96)))
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle140.genPp consumption order exactly, so seed
        // 140001 replays the same 1500 GEN lines drive140.py ran.
        val rnd = Random(140001)
        repeat(1500) {
            val c = genPpCase(rnd)
            val msg = ppMessage(c)
            assertEquals(pinnedPreviewOracle(msg), pinnedPreview(msg))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every type x null + every pool content.
        for (type in MessageType.entries) {
            assertEquals(PinnedPreview.Generic, pinnedPreview(null))
            for (content in PP_CONTENT_POOL) {
                val msg = Message(
                    id = "m1", chatId = "c1", senderId = "u1",
                    content = content, type = type, timestamp = 0,
                )
                assertEquals(pinnedPreviewOracle(msg), pinnedPreview(msg))
            }
        }
        // TEXT x-repeat lengths across the 96-unit truncation edge.
        for (n in 0..130) {
            val msg = Message(
                id = "m1", chatId = "c1", senderId = "u1",
                content = "x".repeat(n), type = MessageType.TEXT, timestamp = 0,
            )
            assertEquals(pinnedPreviewOracle(msg), pinnedPreview(msg))
        }
        // TEXT with every single low unit as a 1-char content.
        for (code in 0x00..0x7F) {
            val msg = Message(
                id = "m1", chatId = "c1", senderId = "u1",
                content = code.toChar().toString(), type = MessageType.TEXT, timestamp = 0,
            )
            assertEquals(pinnedPreviewOracle(msg), pinnedPreview(msg))
        }
    }
}
