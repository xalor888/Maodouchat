package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MediaCenterCategory
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Media-center helpers restated by hand. Ground truth, read from
// MediaCenterModels.kt / MediaCenterSearch.kt / Message.kt /
// the meta JSON decoder / MessageDerivedContent.kt:
//   - parsedContent: text before the last "<meta>" block, HTML entities
//     unescaped (decimal / hex numeric first, named entities after,
//     "&amp;" last).
//   - parsedMeta: the last "<meta>" block decoded with the same
//     per-field rules as the production meta JSON decoder (blank
//     block -> defaults, bad JSON -> the message's own meta field,
//     single bad field never drops the rest).
//   - parsedLocation: LOCATION messages only; kotlinx decode with
//     unknown keys ignored, then latitude/longitude must be finite
//     and in range.
//   - buildMediaCenterItems: media/file/voice/location map to one item
//     each; TEXT yields one LINKS item per distinct URL (trailing
//     punctuation trimmed, length 8..2048), everything sorted by
//     timestamp descending, stable.
//   - mediaCenterItemMatches: five case-insensitive contains checks
//     (content / fileName / link / mime / location label); the
//     content side depends on the category.
internal fun unescapeHtmlEntitiesOracle184(raw: String): String {
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

internal fun parsedContentOracle184(message: Message): String {
    val idx = message.content.lastIndexOf("<meta>")
    val body = if (idx < 0) message.content else message.content.substring(0, idx)
    return unescapeHtmlEntitiesOracle184(body)
}

private val META_JSON184 = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

internal fun JsonElement.asStringOrNullMedia184(): String? =
    if (this is JsonPrimitive) contentOrNull else null

internal fun parsedMetaOracle184(message: Message): MessageMeta {
    val idx = message.content.lastIndexOf("<meta>")
    if (idx < 0) return message.meta
    val json = message.content.substring(idx + "<meta>".length).substringBefore("</meta>")
    return runCatching {
        val element = META_JSON184.parseToJsonElement(json).jsonObject
        val mentions = (element["mentions"] as? JsonArray)
            ?.mapNotNull { it.asStringOrNullMedia184() } ?: emptyList()
        val translations = (element["translations"] as? JsonObject)
            ?.mapValues { (_, value) -> value.asStringOrNullMedia184().orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val aiImageAnalyses = (element["aiImageAnalyses"] as? JsonObject)
            ?.mapValues { (_, value) -> value.asStringOrNullMedia184().orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val aiFileAnalyses = (element["aiFileAnalyses"] as? JsonObject)
            ?.mapValues { (_, value) -> value.asStringOrNullMedia184().orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val inlineKeyboard = (element["inlineKeyboard"] as? JsonArray)?.mapNotNull { rowEl ->
            val rowArr = rowEl as? JsonArray ?: return@mapNotNull null
            rowArr.mapNotNull { btnEl ->
                val o = btnEl as? JsonObject ?: return@mapNotNull null
                val t = o["text"]?.asStringOrNullMedia184().orEmpty()
                val d = o["callbackData"]?.asStringOrNullMedia184()
                    ?: o["callback_data"]?.asStringOrNullMedia184().orEmpty()
                if (t.isBlank()) null
                else com.maodouchat.core.model.InlineKeyboardButton(text = t.take(64), callbackData = d.take(128))
            }.takeIf { it.isNotEmpty() }
        }?.filter { it.isNotEmpty() } ?: emptyList()
        MessageMeta(
            mentions = mentions,
            replyToId = element["replyToId"]?.asStringOrNullMedia184(),
            forwardedFrom = element["forwardedFrom"]?.asStringOrNullMedia184(),
            voiceTranscript = element["voiceTranscript"]?.asStringOrNullMedia184(),
            voiceDurationMs = element["voiceDurationMs"]?.asStringOrNullMedia184()?.toLongOrNull(),
            translations = translations,
            preferredTranslationLanguage = element["preferredTranslationLanguage"]?.asStringOrNullMedia184(),
            aiImageAnalyses = aiImageAnalyses,
            preferredImageAnalysisMode = element["preferredImageAnalysisMode"]?.asStringOrNullMedia184(),
            aiFileAnalyses = aiFileAnalyses,
            preferredFileAnalysisMode = element["preferredFileAnalysisMode"]?.asStringOrNullMedia184(),
            aiFileLastQuestion = element["aiFileLastQuestion"]?.asStringOrNullMedia184(),
            aiAssisted = element["aiAssisted"]?.asStringOrNullMedia184()?.toBooleanStrictOrNull() ?: false,
            aiAssistantMode = element["aiAssistantMode"]?.asStringOrNullMedia184(),
            fileName = element["fileName"]?.asStringOrNullMedia184(),
            fileMimeType = element["fileMimeType"]?.asStringOrNullMedia184(),
            fileSizeBytes = element["fileSizeBytes"]?.asStringOrNullMedia184()?.toLongOrNull(),
            attachmentId = element["attachmentId"]?.asStringOrNullMedia184(),
            attachmentKeyBase64 = element["attachmentKeyBase64"]?.asStringOrNullMedia184(),
            attachmentIvBase64 = element["attachmentIvBase64"]?.asStringOrNullMedia184(),
            attachmentCipherSha256 = element["attachmentCipherSha256"]?.asStringOrNullMedia184(),
            attachmentPlainSha256 = element["attachmentPlainSha256"]?.asStringOrNullMedia184(),
            attachmentCipherSize = element["attachmentCipherSize"]?.asStringOrNullMedia184()?.toLongOrNull(),
            markdown = element["markdown"]?.asStringOrNullMedia184()?.toBooleanStrictOrNull() ?: false,
            viewOnce = element["viewOnce"]?.asStringOrNullMedia184()?.toBooleanStrictOrNull() ?: false,
            viewOnceOpened = element["viewOnceOpened"]?.asStringOrNullMedia184()?.toBooleanStrictOrNull() ?: false,
            silent = element["silent"]?.asStringOrNullMedia184()?.toBooleanStrictOrNull() ?: false,
            spoilerMedia = element["spoilerMedia"]?.asStringOrNullMedia184()?.toBooleanStrictOrNull() ?: false,
            spoilerRevealed = element["spoilerRevealed"]?.asStringOrNullMedia184()?.toBooleanStrictOrNull() ?: false,
            inlineKeyboard = inlineKeyboard,
            forceReply = element["forceReply"]?.asStringOrNullMedia184()?.toBooleanStrictOrNull() ?: false,
        )
    }.getOrElse {
        if (json.isBlank()) MessageMeta() else message.meta
    }
}

private val LOCATION_JSON184 = Json { ignoreUnknownKeys = true }

internal fun parsedLocationOracle184(message: Message): LocationPayload? {
    if (message.type != MessageType.LOCATION) return null
    return runCatching {
        LOCATION_JSON184.decodeFromString(LocationPayload.serializer(), parsedContentOracle184(message))
    }.getOrNull()?.takeIf {
        it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
    }
}

private val WEB_URL_REGEX184 = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
private val TRAILING_URL_PUNCTUATION184 = setOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '。', '，', '！', '？', '；', '：')

internal fun buildMediaCenterItemsOracle184(messages: List<Message>): List<MediaCenterItem> =
    messages.flatMap { message ->
        when (message.type) {
            MessageType.IMAGE, MessageType.GIF, MessageType.VIDEO, MessageType.STICKER ->
                listOf(MediaCenterItem(message, MediaCenterCategory.MEDIA))
            MessageType.FILE -> listOf(MediaCenterItem(message, MediaCenterCategory.FILES))
            MessageType.VOICE -> listOf(MediaCenterItem(message, MediaCenterCategory.VOICE))
            MessageType.LOCATION -> listOf(MediaCenterItem(message, MediaCenterCategory.LOCATION))
            MessageType.TEXT -> WEB_URL_REGEX184.findAll(parsedContentOracle184(message))
                .mapNotNull { match ->
                    val url = match.value.trimEnd { it in TRAILING_URL_PUNCTUATION184 }
                        .takeIf { it.length in 8..2_048 }
                    url?.let { MediaCenterItem(message, MediaCenterCategory.LINKS, it) }
                }
                .distinctBy(MediaCenterItem::linkUrl)
                .toList()
            else -> emptyList()
        }
    }.sortedByDescending { it.message.timestamp }

internal fun mediaCenterItemMatchesOracle184(item: MediaCenterItem, query: String): Boolean {
    val message = item.message
    val meta = parsedMetaOracle184(message)
    val fileName = meta.fileName.orEmpty()
    val mime = meta.fileMimeType.orEmpty()
    val link = item.linkUrl.orEmpty()
    val locationLabel = runCatching {
        val payload = parsedLocationOracle184(message)
        payload?.label.orEmpty()
    }.getOrDefault("")
    val content = when (item.category) {
        MediaCenterCategory.LINKS -> link
        MediaCenterCategory.LOCATION -> locationLabel.ifBlank { parsedContentOracle184(message) }
        MediaCenterCategory.FILES -> fileName.ifBlank { parsedContentOracle184(message) }
        else -> listOf(fileName, mime, parsedContentOracle184(message)).joinToString(" ")
    }
    return content.contains(query, ignoreCase = true) ||
        fileName.contains(query, ignoreCase = true) ||
        link.contains(query, ignoreCase = true) ||
        mime.contains(query, ignoreCase = true) ||
        locationLabel.contains(query, ignoreCase = true)
}

internal fun escJsonMedia184(s: String): String {
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
                if (ch.code < 0x20 || ch.code in 0xD800..0xDFFF) {
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

internal fun metaWireJsonMedia184(m: MessageMeta): String {
    val mentions = m.mentions.joinToString(",") { escJsonMedia184(it) }
    val translations = m.translations.entries.joinToString(",") { (k, v) -> "${escJsonMedia184(k)}:${escJsonMedia184(v)}" }
    val aiImage = m.aiImageAnalyses.entries.joinToString(",") { (k, v) -> "${escJsonMedia184(k)}:${escJsonMedia184(v)}" }
    val aiFile = m.aiFileAnalyses.entries.joinToString(",") { (k, v) -> "${escJsonMedia184(k)}:${escJsonMedia184(v)}" }
    val keyboard = m.inlineKeyboard.joinToString(",") { row ->
        "[" + row.joinToString(",") { b ->
            "{\"text\":${escJsonMedia184(b.text)},\"callbackData\":${escJsonMedia184(b.callbackData)}}"
        } + "]"
    }
    fun optS(v: String?): String = v?.let { escJsonMedia184(it) } ?: "null"
    fun optL(v: Long?): String = v?.toString() ?: "null"
    return "{\"mentions\":[$mentions],\"replyToId\":${optS(m.replyToId)}," +
        "\"forwardedFrom\":${optS(m.forwardedFrom)},\"voiceTranscript\":${optS(m.voiceTranscript)}," +
        "\"voiceDurationMs\":${optL(m.voiceDurationMs)},\"translations\":{$translations}," +
        "\"preferredTranslationLanguage\":${optS(m.preferredTranslationLanguage)}," +
        "\"aiImageAnalyses\":{$aiImage},\"preferredImageAnalysisMode\":${optS(m.preferredImageAnalysisMode)}," +
        "\"aiFileAnalyses\":{$aiFile},\"preferredFileAnalysisMode\":${optS(m.preferredFileAnalysisMode)}," +
        "\"aiFileLastQuestion\":${optS(m.aiFileLastQuestion)},\"aiAssisted\":${m.aiAssisted}," +
        "\"aiAssistantMode\":${optS(m.aiAssistantMode)},\"fileName\":${optS(m.fileName)}," +
        "\"fileMimeType\":${optS(m.fileMimeType)},\"fileSizeBytes\":${optL(m.fileSizeBytes)}," +
        "\"attachmentId\":${optS(m.attachmentId)},\"attachmentKeyBase64\":${optS(m.attachmentKeyBase64)}," +
        "\"attachmentIvBase64\":${optS(m.attachmentIvBase64)},\"attachmentCipherSha256\":${optS(m.attachmentCipherSha256)}," +
        "\"attachmentPlainSha256\":${optS(m.attachmentPlainSha256)},\"attachmentCipherSize\":${optL(m.attachmentCipherSize)}," +
        "\"markdown\":${m.markdown},\"viewOnce\":${m.viewOnce},\"viewOnceOpened\":${m.viewOnceOpened}," +
        "\"silent\":${m.silent},\"spoilerMedia\":${m.spoilerMedia},\"spoilerRevealed\":${m.spoilerRevealed}," +
        "\"inlineKeyboard\":[$keyboard],\"forceReply\":${m.forceReply}}"
}

internal fun messageWireJson184(m: Message): String {
    val reactions = m.reactions.joinToString(",") { r ->
        "{\"userId\":${escJsonMedia184(r.userId)},\"emoji\":${escJsonMedia184(r.emoji)},\"reactedAt\":${r.reactedAt}}"
    }
    val edited = m.editedAt?.toString() ?: "null"
    val expires = m.expiresAt?.toString() ?: "null"
    return "{\"id\":${escJsonMedia184(m.id)},\"chatId\":${escJsonMedia184(m.chatId)}," +
        "\"senderId\":${escJsonMedia184(m.senderId)},\"content\":${escJsonMedia184(m.content)}," +
        "\"type\":${escJsonMedia184(m.type.name)},\"timestamp\":${m.timestamp}," +
        "\"status\":${escJsonMedia184(m.status.name)},\"editedAt\":$edited," +
        "\"starred\":${m.starred},\"meta\":${escJsonMedia184(metaWireJsonMedia184(m.meta))}," +
        "\"reactions\":[$reactions],\"expiresAt\":$expires," +
        "\"sealedSender\":${m.sealedSender}}"
}

internal fun mediaCenterItemWireJson184(item: MediaCenterItem): String =
    "{\"message\":${messageWireJson184(item.message)}," +
        "\"category\":${escJsonMedia184(item.category.name)}," +
        "\"linkUrl\":${item.linkUrl?.let { escJsonMedia184(it) } ?: "null"}}"

internal fun buildMessagesWireJson184(messages: List<Message>): String =
    "[" + messages.joinToString(",") { messageWireJson184(it) } + "]"

internal fun itemsWireJson184(items: List<MediaCenterItem>): String =
    "[" + items.joinToString(",") { mediaCenterItemWireJson184(it) } + "]"

// ---------- production ----------

internal fun buildItemsProduction184(messages: List<Message>): List<MediaCenterItem> =
    buildMediaCenterItems(messages)

internal fun matchesProduction184(item: MediaCenterItem, query: String): Boolean =
    mediaCenterItemMatches(item, query)

// ---------- generators ----------

internal val MEDIA_TYPES184: List<MessageType> = MessageType.entries

internal val MEDIA_CONTENTS184: List<String> = listOf(
    "hello world",
    "see https://example.com/a and http://x.co/b.",
    "visit HTTPS://EXAMPLE.COM/UPPER?x=1!",
    "two links https://a.com/1 https://a.com/1 and https://b.com/2,",
    "short http://a ok",
    "https://example.com/" + "p".repeat(2030),
    "https://example.com/" + "p".repeat(2040),
    "angle <https://example.com/a> brackets",
    "trailing https://example.com/a...,,,!?",
    "no url here",
    "",
    " ",
    "image file",
    "voice memo",
    "caf\u00e9 r\u00e9sum\u00e9",
    "a &amp; b &#65;&#x42; &lt;x&gt;",
    "prefix <meta> literal in body",
    "https://example.com/a\u3002\u4e2d\u6587",
    "emoji \uD83D\uDE00 https://example.com/e",
)

internal val MEDIA_META_JSON184: List<String> = listOf(
    "{\"fileName\":\"report.pdf\",\"fileMimeType\":\"application/pdf\"}",
    "{\"fileName\":\"IMG_01.JPG\",\"fileMimeType\":\"image/jpeg\"}",
    "{\"fileName\":\"\",\"fileMimeType\":\"\"}",
    "{\"fileName\":123,\"fileMimeType\":true}",
    "{\"fileName\":\"  \",\"fileMimeType\":\"text/plain\"}",
    "{\"voiceTranscript\":\"hello world\",\"translations\":{\"en\":\"hello\",\"zh\":\"\\u4f60\\u597d\"}}",
    "{\"translations\":{\"en\":\"\",\"fr\":\"bonjour\"}}",
    "{\"mentions\":[\"alice\",42,null]}",
    "{\"voiceDurationMs\":123,\"aiAssisted\":\"true\",\"markdown\":\"yes\"}",
    "{\"voiceDurationMs\":\"1.5\",\"aiAssisted\":\"True\"}",
    "{\"inlineKeyboard\":[[{\"text\":\"ok\",\"callbackData\":\"cb\"}],[{\"text\":\"\",\"callbackData\":\"x\"}]]}",
    "{\"inlineKeyboard\":[[{\"text\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"callbackData\":\"d\"}]]}",
    "{}",
    "{",
    "{\"fileName\":}",
    "{\"a\":01}",
    "{\"a\":NaN}",
    "123",
    "[1,2]",
    "\"str\"",
    "{\"fileName\":\"caf\\u00e9.pdf\"}",
)

internal val MEDIA_LOCATION_JSON184: List<String> = listOf(
    "{\"latitude\":39.9,\"longitude\":116.4,\"label\":\"Beijing\"}",
    "{\"latitude\":-90.0,\"longitude\":-180.0,\"label\":\"edge\"}",
    "{\"latitude\":90.0,\"longitude\":180.0,\"label\":\"\"}",
    "{\"latitude\":90.1,\"longitude\":0.0,\"label\":\"north\"}",
    "{\"latitude\":0.0,\"longitude\":180.1,\"label\":\"east\"}",
    "{\"latitude\":\"39.9\",\"longitude\":116.4,\"label\":\"str\"}",
    "{\"latitude\":39.9}",
    "{\"longitude\":116.4}",
    "{\"latitude\":null,\"longitude\":null}",
    "{\"latitude\":39.9,\"longitude\":116.4,\"accuracyMeters\":\"bad\",\"label\":\"badacc\"}",
    "{\"latitude\":39.9,\"longitude\":116.4,\"unknown\":1,\"label\":\"extra\"}",
    "{\"latitude\":1e400,\"longitude\":0.0,\"label\":\"inf\"}",
    "{\"latitude\":39.9,\"longitude\":116.4,\"label\":\"  \"}",
    "{\"latitude\":39.9,\"longitude\":116.4}",
    "not json",
    "{\"latitude\":39.9,\"longitude\":116.4,\"label\":\"caf\\u00e9\"}",
)

internal val MEDIA_QUERIES184: List<String> = listOf(
    "", " ", "hello", "HELLO", "Hello", "example", "EXAMPLE", "com",
    "pdf", "PDF", "jpg", "image", "jpeg", "report", "a", "http",
    "https://example.com/a", "beijing", "BEIJING", "caf\u00e9", "CAF\u00c9",
    "voice", "memo", "x", "\u00df", "SS", "STRASSE", "stra\u00dfe",
    "\u0130", "i", "\u03a3", "\u03c3", "\u03c2", "emoji", "\uD83D\uDE00",
    "<meta>", "&amp;", "65", "A",
)

internal fun randomMeta184(rnd: Random): MessageMeta = MessageMeta(
    mentions = List(rnd.nextInt(0, 3)) { listOf("alice", "bob").random(rnd) },
    voiceTranscript = if (rnd.nextBoolean()) null else listOf("hello world", "  ", "").random(rnd),
    translations = mapOf(
        "en" to listOf("hello", "", "  ").random(rnd),
        "zh" to listOf("\u4f60\u597d", "").random(rnd),
    ).filterValues { rnd.nextBoolean() },
    preferredTranslationLanguage = if (rnd.nextBoolean()) null else listOf("en", "zh", "fr").random(rnd),
    aiImageAnalyses = if (rnd.nextBoolean()) emptyMap() else mapOf("mode" to "a cat"),
    aiFileAnalyses = if (rnd.nextBoolean()) emptyMap() else mapOf("q" to "answer"),
    aiFileLastQuestion = if (rnd.nextBoolean()) null else "what is this?",
    fileName = if (rnd.nextBoolean()) null else listOf("report.pdf", "IMG_01.JPG", "", "  ").random(rnd),
    fileMimeType = if (rnd.nextBoolean()) null else listOf("application/pdf", "image/jpeg", "text/plain").random(rnd),
)

internal fun randomMessageMedia184(rnd: Random): Message {
    val body = MEDIA_CONTENTS184.random(rnd)
    val r = rnd.nextInt(0, 10)
    val content = when {
        r <= 3 -> body + "<meta>" + MEDIA_META_JSON184.random(rnd) + "</meta>"
        r == 4 -> body + "<meta>" + MEDIA_META_JSON184.random(rnd)
        r == 5 -> body + "<meta>" + MEDIA_LOCATION_JSON184.random(rnd) + "</meta>"
        r == 6 -> MEDIA_LOCATION_JSON184.random(rnd)
        else -> body
    }
    val type = if (r == 5 || r == 6) MessageType.LOCATION else MEDIA_TYPES184.random(rnd)
    return Message(
        id = "m${rnd.nextInt(0, 8)}",
        chatId = "c1",
        senderId = listOf("alice", "bob", "carol").random(rnd),
        content = content,
        type = type,
        timestamp = rnd.nextLong(0L, 200000L),
        status = com.maodouchat.core.model.MessageStatus.entries.random(rnd),
        meta = randomMeta184(rnd),
    )
}

internal fun randomBuildMessages184(rnd: Random): List<Message> =
    List(rnd.nextInt(0, 6)) { randomMessageMedia184(rnd) }

internal fun randomMediaCenterItem184(rnd: Random): MediaCenterItem {
    val message = randomMessageMedia184(rnd)
    val category = MediaCenterCategory.entries.random(rnd)
    val link = if (category == MediaCenterCategory.LINKS && rnd.nextBoolean()) {
        listOf("https://example.com/a", "http://x.co/b", null).random(rnd)
    } else {
        null
    }
    return MediaCenterItem(message, category, link)
}

internal data class MatchCase184(val item: MediaCenterItem, val query: String)

internal fun randomMatchCase184(rnd: Random): MatchCase184 =
    MatchCase184(randomMediaCenterItem184(rnd), MEDIA_QUERIES184.random(rnd))

internal fun matchCaseWireJson184(c: MatchCase184): String =
    "{\"item\":${mediaCenterItemWireJson184(c.item)},\"query\":${escJsonMedia184(c.query)}}"

internal fun msgMedia184(
    content: String,
    type: MessageType = MessageType.TEXT,
    timestamp: Long = 1000L,
    meta: MessageMeta = MessageMeta(),
    id: String = "m1",
): Message = Message(
    id = id, chatId = "c1", senderId = "alice", content = content,
    type = type, timestamp = timestamp, meta = meta,
)

class MediaCenterFuzzTest {

    private fun checkBuild184(messages: List<Message>) {
        assertEquals(
            buildMediaCenterItemsOracle184(messages),
            buildItemsProduction184(messages),
            "build($messages)",
        )
    }

    private fun checkMatch184(c: MatchCase184) {
        assertEquals(
            mediaCenterItemMatchesOracle184(c.item, c.query),
            matchesProduction184(c.item, c.query),
            "match($c)",
        )
    }

    @Test
    fun pinnedMediaCenter() {
        // URL extraction: scheme case, duplicates, punctuation, length bounds.
        checkBuild184(listOf(msgMedia184("see https://example.com/a and http://x.co/b.")))
        checkBuild184(listOf(msgMedia184("visit HTTPS://EXAMPLE.COM/UPPER?x=1!")))
        checkBuild184(listOf(msgMedia184("two links https://a.com/1 https://a.com/1 and https://b.com/2,")))
        checkBuild184(listOf(msgMedia184("short http://a ok")))
        checkBuild184(listOf(msgMedia184("https://example.com/" + "p".repeat(2030))))
        checkBuild184(listOf(msgMedia184("https://example.com/" + "p".repeat(2040))))
        checkBuild184(listOf(msgMedia184("angle <https://example.com/a> brackets")))
        checkBuild184(listOf(msgMedia184("trailing https://example.com/a...,,,!?")))
        checkBuild184(listOf(msgMedia184("no url here")))
        checkBuild184(listOf(msgMedia184("")))
        // One item per media/file/voice/location message; others map to none.
        for (type in MEDIA_TYPES184) {
            checkBuild184(listOf(msgMedia184("x", type = type, timestamp = 100L)))
        }
        // Sort is timestamp-descending and stable.
        checkBuild184(
            listOf(
                msgMedia184("https://a.com/1", timestamp = 100L, id = "m1"),
                msgMedia184("https://b.com/2", timestamp = 300L, id = "m2"),
                msgMedia184("https://c.com/3", timestamp = 300L, id = "m3"),
                msgMedia184("x", type = MessageType.IMAGE, timestamp = 200L, id = "m4"),
            )
        )
        // Meta block feeds fileName/mime; bad JSON falls back to the meta field.
        val meta = MessageMeta(fileName = "fallback.pdf", fileMimeType = "application/pdf")
        checkBuild184(listOf(msgMedia184("x<meta>{\"fileName\":\"real.pdf\"}</meta>", type = MessageType.FILE, meta = meta)))
        checkBuild184(listOf(msgMedia184("x<meta>{bad</meta>", type = MessageType.FILE, meta = meta)))
        checkBuild184(listOf(msgMedia184("x<meta></meta>", type = MessageType.FILE, meta = meta)))
        // Match: every category x a few queries, plus case-folding edges.
        val item = MediaCenterItem(
            msgMedia184(
                "Hello World<meta>{\"fileName\":\"Report.PDF\",\"fileMimeType\":\"application/pdf\"}</meta>",
                type = MessageType.FILE,
            ),
            MediaCenterCategory.FILES,
        )
        for (q in listOf("hello", "HELLO", "report", "REPORT", "pdf", "PDF", "application", "world", "")) {
            checkMatch184(MatchCase184(item, q))
        }
        val locItem = MediaCenterItem(
            msgMedia184("{\"latitude\":39.9,\"longitude\":116.4,\"label\":\"Beijing\"}", type = MessageType.LOCATION),
            MediaCenterCategory.LOCATION,
        )
        for (q in listOf("beijing", "BEIJING", "39.9", "")) {
            checkMatch184(MatchCase184(locItem, q))
        }
        val linkItem = MediaCenterItem(
            msgMedia184("see https://Example.COM/Path", type = MessageType.TEXT),
            MediaCenterCategory.LINKS,
            "https://Example.COM/Path",
        )
        for (q in listOf("example", "EXAMPLE", "path", "PATH", "https")) {
            checkMatch184(MatchCase184(linkItem, q))
        }
        // Case-folding edges: sharp s, dotted capital I, sigma variants.
        val foldItem = MediaCenterItem(msgMedia184("Stra\u00dfe \u0130stanbul \u03a3\u03c3\u03c2"), MediaCenterCategory.MEDIA)
        for (q in listOf("strasse", "STRASSE", "i", "\u0130", "\u03c3", "\u03a3", "\u03c2")) {
            checkMatch184(MatchCase184(foldItem, q))
        }
    }

    @Test
    fun randomMediaCenterMatchProduction() {
        val rnd = Random(184003)
        repeat(1500) { checkBuild184(randomBuildMessages184(rnd)) }
        repeat(2000) { checkMatch184(randomMatchCase184(rnd)) }
    }

    @Test
    fun sweepMediaCenterBoundaries() {
        // Every type through build, alone and mixed.
        for (type in MEDIA_TYPES184) {
            checkBuild184(listOf(msgMedia184("https://a.com/1", type = type)))
        }
        // Every meta JSON variant through build as FILE, every location
        // JSON variant through build as LOCATION.
        for (meta in MEDIA_META_JSON184) {
            checkBuild184(listOf(msgMedia184("x<meta>$meta</meta>", type = MessageType.FILE)))
        }
        for (loc in MEDIA_LOCATION_JSON184) {
            checkBuild184(listOf(msgMedia184(loc, type = MessageType.LOCATION)))
            checkMatch184(
                MatchCase184(
                    MediaCenterItem(msgMedia184(loc, type = MessageType.LOCATION), MediaCenterCategory.LOCATION),
                    "label",
                )
            )
        }
        // Every query against a fixed mixed item set.
        val items = listOf(
            MediaCenterItem(msgMedia184("Hello World", type = MessageType.TEXT), MediaCenterCategory.MEDIA),
            MediaCenterItem(
                msgMedia184("x<meta>{\"fileName\":\"Report.PDF\"}</meta>", type = MessageType.FILE),
                MediaCenterCategory.FILES,
            ),
            MediaCenterItem(msgMedia184("https://Example.COM/Path", type = MessageType.TEXT), MediaCenterCategory.LINKS, "https://Example.COM/Path"),
        )
        for (item in items) {
            for (q in MEDIA_QUERIES184) checkMatch184(MatchCase184(item, q))
        }
        // URL length boundary: total length 7, 8, 2048, 2049.
        for (n in listOf(0, 1, 2041, 2042)) {
            checkBuild184(listOf(msgMedia184("https://x.co/" + "p".repeat(n))))
        }
    }
}
