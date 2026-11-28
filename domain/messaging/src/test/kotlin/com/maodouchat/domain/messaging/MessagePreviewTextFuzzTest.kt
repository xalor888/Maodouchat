package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// unescapeHtmlEntities restated by hand: numeric decimal entities,
// then numeric hex entities, then the named replacements in order.
// A code outside 1..0x10FFFF leaves the entity untouched; a lone
// surrogate code point becomes one lone surrogate unit, exactly as
// String(intArrayOf(code), 0, 1) builds it.
internal val NUMERIC_DECIMAL174 = Regex("&#(\\d{1,7});")
internal val NUMERIC_HEX174 = Regex("&#x([0-9a-fA-F]{1,6});", RegexOption.IGNORE_CASE)

internal fun unescapeHtmlEntitiesOracle174(raw: String): String {
    if (raw.isEmpty() || raw.indexOf('&') < 0) return raw
    var out = raw
    out = NUMERIC_DECIMAL174.replace(out) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { code ->
            String(intArrayOf(code), 0, 1)
        } ?: match.value
    }
    out = NUMERIC_HEX174.replace(out) { match ->
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

// visiblePreviewText restated by hand: strip at the last <meta>,
// trim, blank or a local media URI shows nothing, else unescape.
internal fun visiblePreviewTextOracle174(raw: String): String {
    val withoutMeta = raw.lastIndexOf(Message.META_TAG_PREFIX).let { idx ->
        if (idx < 0) raw else raw.substring(0, idx)
    }.trim()
    if (withoutMeta.isBlank()) return ""
    if (looksLikeLocalMediaUriOracle174(withoutMeta)) return ""
    return unescapeHtmlEntitiesOracle174(withoutMeta)
}

internal fun looksLikeLocalMediaUriOracle174(content: String): Boolean {
    val t = content.trim()
    return t.startsWith("file:") ||
        t.startsWith("content:") ||
        t.startsWith("maodou-attachment://")
}

// isSignalWireEnvelope restated by hand: a JSON object or array
// envelope that carries Signal ciphertext features; a "signal-"
// body needs one of algorithm / distributionMessage / ciphertext /
// senderKey, otherwise ciphertext plus a device / entries / type
// marker (or a distributionMessage) is required.
internal fun isSignalWireEnvelopeOracle174(content: String): Boolean {
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

// redactedIfWire restated by hand: a blank visible body shows the
// placeholder; a wire envelope (in the raw content or the visible
// body) shows the placeholder; anything else shows the body.
internal fun redactedIfWireOracle174(content: String, placeholder: String): String {
    val body = visiblePreviewTextOracle174(content)
    if (body.isBlank()) return placeholder
    return if (isSignalWireEnvelopeOracle174(content) ||
        isSignalWireEnvelopeOracle174(body)
    ) placeholder else body
}

// parsedContent restated by hand: strip at the last <meta>, then
// unescape; unlike visiblePreviewText it does not trim.
internal fun parsedContentOracle174(content: String): String =
    content.lastIndexOf(Message.META_TAG_PREFIX).let { idx ->
        val body = if (idx < 0) content else content.substring(0, idx)
        unescapeHtmlEntitiesOracle174(body)
    }

// replyOrQuote restated by hand: media types take the label, revoked
// and Sender Key rows take the placeholder, everything else goes
// through the wire redaction; newlines are flattened, the result is
// trimmed, and a blank result falls back to the placeholder as-is.
internal fun replyOrQuoteOracle174(
    message: Message,
    mediaLabel: (MessageType) -> String,
    encryptedPlaceholder: String,
): String {
    val labeled = when (message.type) {
        MessageType.IMAGE,
        MessageType.GIF,
        MessageType.STICKER,
        MessageType.VOICE,
        MessageType.VIDEO,
        MessageType.FILE,
        MessageType.LOCATION -> mediaLabel(message.type)
        MessageType.REVOKED -> encryptedPlaceholder
        MessageType.SK_DIST -> encryptedPlaceholder
        else -> redactedIfWireOracle174(
            parsedContentOracle174(message.content),
            encryptedPlaceholder,
        )
    }
    return labeled.replace('\n', ' ').trim().ifBlank { encryptedPlaceholder }
}

// The media label the tests pass in: one fixed label per type, with a
// newline, surrounding spaces and blank labels mixed in so the
// post-processing (flatten, trim, blank fallback) is exercised on
// the media path too.
internal val MEDIA_LABEL174: (MessageType) -> String = { type ->
    when (type) {
        MessageType.IMAGE -> "[image]"
        MessageType.GIF -> "[gif\n]"
        MessageType.STICKER -> "  [sticker]  "
        MessageType.LOCATION -> ""
        MessageType.VOICE -> "   "
        MessageType.VIDEO -> "[video]"
        MessageType.FILE -> "[fi\nle]"
        else -> "[media]"
    }
}

// Production replyOrQuote returns the reply / quote preview text.
internal fun replyOrQuoteProduction174(
    message: Message,
    mediaLabel: (MessageType) -> String,
    encryptedPlaceholder: String,
): String = MessagePreviewText.replyOrQuote(message, mediaLabel, encryptedPlaceholder)

// ---------- generators ----------

// Canonical JSON string escaping, same as Python json.dumps with
// ensure_ascii=False, so wire JSON survives quotes and control chars.
internal fun escJsonPreview174(s: String): String {
    val sb = StringBuilder()
    sb.append("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\u0008' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch.code < 0x20) {
                    sb.append("\\u")
                    val v = ch.code
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

internal data class PreviewCase174(
    val content: String,
    val type: MessageType,
    val placeholder: String,
)

internal val PREVIEW_TYPE_POOL174: List<MessageType> = MessageType.entries.toList()

internal val PREVIEW_CONTENT_POOL174: List<String> = listOf(
    "hello", "", " ", "  hello  ", "a\nb", "a\n\nb", "\n", "line1\nline2",
    "{\"ciphertext\":\"abc\"}",
    "{\"senderDeviceId\":1,\"ciphertext\":\"x\"}",
    "{\"senderDeviceId\":1,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertext\":\"x\"}]}",
    "{\"distributionMessage\":\"x\"}",
    "{\"algorithm\":\"x\"}",
    "signal-{\"algorithm\":\"x\"}",
    "{\"signal-x\":1}",
    "[{\"ciphertext\":\"x\",\"senderDeviceId\":1}]",
    "[\"x\"]", "{", "[{", "  {\"ciphertext\":\"x\"}",
    "{\"ciphertextType\":\"x\"}",
    "{\"senderKey\":\"x\"}",
    "{\"ciphertext\":\"x\",\"senderKey\":\"y\"}",
    "{\"foo\":1}", "signal-{}", "  [{\"ciphertext\":\"x\"}]",
    "hello<meta>{}", "hello<meta>{\"fileName\":\"a.pdf\"}</meta>",
    "a<meta>b<meta>c", "<meta>", "</meta>", "hello</meta>", "a<META>b", "a<meta",
    "text<meta>{\"x\":1}", "a<meta>b",
    "&#10;", "&#x41;", "&#65;", "&amp;", "&lt;tag&gt;", "&nbsp;x",
    "&#0;", "&#1114112;", "&#55296;", "&#xD800;", "&#x110000;", "&#X41;",
    "&#;", "&#x;", "&quot;q&quot;", "&#39;", "&apos;", "a & b", "&unknown;",
    "&#0000065;", "&#x000041;", "&#38;#x41;", "&#x26;#38;",
    "file:///a/b.jpg", "content://media/1", "maodou-attachment://abc",
    "  file:///x  ", "FILE:///x",
    "\u00a0", "\u2007", "\u0085", "\t", " \t\n ",
    "https://example.com/a", "{\"ciphertext\":\"x\"} tail",
    "tail {\"ciphertext\":\"x\"}",
)

internal val PREVIEW_PLACEHOLDER_POOL174: List<String> = listOf(
    "[encrypted]", "", " ", "  ", "[en\ncrypted]", "  [enc]  ", "🎉", "\n"
)

internal fun buildPreviewMessage174(c: PreviewCase174): Message = Message(
    id = "m1",
    chatId = "c1",
    senderId = "u1",
    content = c.content,
    type = c.type,
)

internal fun randomPreviewCase174(rnd: Random): PreviewCase174 = PreviewCase174(
    content = PREVIEW_CONTENT_POOL174[rnd.nextInt(PREVIEW_CONTENT_POOL174.size)],
    type = PREVIEW_TYPE_POOL174[rnd.nextInt(PREVIEW_TYPE_POOL174.size)],
    placeholder = PREVIEW_PLACEHOLDER_POOL174[rnd.nextInt(PREVIEW_PLACEHOLDER_POOL174.size)],
)

internal fun previewWireJson174(c: PreviewCase174): String =
    "{\"content\":${escJsonPreview174(c.content)}," +
        "\"type\":${escJsonPreview174(c.type.name)}," +
        "\"placeholder\":${escJsonPreview174(c.placeholder)}}"

class MessagePreviewTextFuzzTest {

    private fun checkPreview174(c: PreviewCase174) {
        val message = buildPreviewMessage174(c)
        assertEquals(
            replyOrQuoteOracle174(message, MEDIA_LABEL174, c.placeholder),
            replyOrQuoteProduction174(message, MEDIA_LABEL174, c.placeholder),
            "preview($c)"
        )
    }

    @Test
    fun pinnedBoundaries() {
        // Media labels: plain, newline (flattened), padded (trimmed),
        // blank (falls back to the placeholder).
        for (type in listOf(
            MessageType.IMAGE, MessageType.GIF, MessageType.STICKER,
            MessageType.LOCATION, MessageType.VOICE, MessageType.VIDEO,
            MessageType.FILE
        )) {
            checkPreview174(PreviewCase174("ignored body", type, "[encrypted]"))
        }
        // Revoked and Sender Key rows always show the placeholder.
        checkPreview174(PreviewCase174("whatever", MessageType.REVOKED, "[encrypted]"))
        checkPreview174(PreviewCase174("whatever", MessageType.SK_DIST, "[encrypted]"))
        // The placeholder itself goes through flatten + trim, and a
        // blank placeholder comes back as-is.
        checkPreview174(PreviewCase174("whatever", MessageType.REVOKED, "[en\ncrypted]"))
        checkPreview174(PreviewCase174("whatever", MessageType.REVOKED, "   "))
        // Wire envelopes never surface; plain JSON without a Signal
        // feature stays visible.
        checkPreview174(PreviewCase174("{\"ciphertext\":\"abc\"}", MessageType.TEXT, "[encrypted]"))
        checkPreview174(PreviewCase174("{\"foo\":1}", MessageType.TEXT, "[encrypted]"))
        checkPreview174(PreviewCase174("{\"ciphertext\":\"x\"}", MessageType.TEXT, "[encrypted]"))
        checkPreview174(PreviewCase174("hello", MessageType.TEXT, "[encrypted]"))
        checkPreview174(PreviewCase174("a\nb", MessageType.TEXT, "[encrypted]"))
        checkPreview174(PreviewCase174("  hello  ", MessageType.TEXT, "[encrypted]"))
        checkPreview174(PreviewCase174("   ", MessageType.TEXT, "[encrypted]"))
        checkPreview174(PreviewCase174("", MessageType.TEXT, "[encrypted]"))
        // Meta is stripped twice (parsedContent, then the redaction);
        // entities are unescaped on both passes.
        checkPreview174(PreviewCase174("hello<meta>{}", MessageType.TEXT, "[encrypted]"))
        checkPreview174(PreviewCase174("a<meta>b<meta>c", MessageType.TEXT, "[encrypted]"))
        checkPreview174(PreviewCase174("&#65;&#x42;", MessageType.TEXT, "[encrypted]"))
        checkPreview174(PreviewCase174("&lt;hi&gt;", MessageType.TEXT, "[encrypted]"))
        // A local media URI body shows the placeholder.
        checkPreview174(PreviewCase174("file:///a/b.jpg", MessageType.TEXT, "[encrypted]"))
        // NUDGE / SYSTEM / MARKDOWN take the same else branch as TEXT.
        checkPreview174(PreviewCase174("hello", MessageType.NUDGE, "[encrypted]"))
        checkPreview174(PreviewCase174("hello", MessageType.SYSTEM, "[encrypted]"))
        checkPreview174(PreviewCase174("hello", MessageType.MARKDOWN, "[encrypted]"))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(174003)
        repeat(4000) { checkPreview174(randomPreviewCase174(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Every content x every type, with the default placeholder,
        // plus every placeholder x a fixed content per type.
        for (content in PREVIEW_CONTENT_POOL174) {
            for (type in PREVIEW_TYPE_POOL174) {
                checkPreview174(PreviewCase174(content, type, "[encrypted]"))
            }
        }
        for (placeholder in PREVIEW_PLACEHOLDER_POOL174) {
            for (type in PREVIEW_TYPE_POOL174) {
                checkPreview174(PreviewCase174("hello", type, placeholder))
            }
        }
    }
}
