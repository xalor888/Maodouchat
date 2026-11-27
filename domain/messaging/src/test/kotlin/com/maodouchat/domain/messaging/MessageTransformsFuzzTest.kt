package com.maodouchat.domain.messaging

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

// Message-transform helpers restated by hand. Ground truth, read from
// MessageOptimisticTransforms.kt / MessageTranslationDisplay.kt /
// MessageSearchText.kt / ForwardPreviewText.kt (plus the same
// parsedContent / parsedMeta / parsedLocation delegates as the media
// center file):
//   - toRevokedPlaceholder: content and type replaced, meta reset to
//     defaults; every other field untouched.
//   - toOptimisticEdit: next revision is max(timestamp, editedAt) + 1,
//     saturating at Long.MAX_VALUE.
//   - displayedTranslation: preferred language first, then Chinese,
//     then the last non-blank translation in map order.
//   - semanticSearchText: content part by type (TEXT/MARKDOWN parsed
//     body, LOCATION label, NUDGE raw body, anything else nothing),
//     then voice transcript, translations, image analyses, file
//     analyses, last file question; trimmed, blanks dropped,
//     de-duplicated in order, joined with newlines, cut to
//     maxLength clamped to 1..10000.
//   - forwardPreviewText: media types map to their preview label,
//     anything else maps to null.
internal fun unescapeHtmlEntitiesOracle184t(raw: String): String {
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

internal fun parsedContentOracle184t(message: Message): String {
    val idx = message.content.lastIndexOf("<meta>")
    val body = if (idx < 0) message.content else message.content.substring(0, idx)
    return unescapeHtmlEntitiesOracle184t(body)
}

private val META_JSON184t = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

internal fun JsonElement.asStringOrNullTransforms184t(): String? =
    if (this is JsonPrimitive) contentOrNull else null

internal fun parsedMetaOracle184t(message: Message): MessageMeta {
    val idx = message.content.lastIndexOf("<meta>")
    if (idx < 0) return message.meta
    val json = message.content.substring(idx + "<meta>".length).substringBefore("</meta>")
    return runCatching {
        val element = META_JSON184t.parseToJsonElement(json).jsonObject
        val mentions = (element["mentions"] as? JsonArray)
            ?.mapNotNull { it.asStringOrNullTransforms184t() } ?: emptyList()
        val translations = (element["translations"] as? JsonObject)
            ?.mapValues { (_, value) -> value.asStringOrNullTransforms184t().orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val aiImageAnalyses = (element["aiImageAnalyses"] as? JsonObject)
            ?.mapValues { (_, value) -> value.asStringOrNullTransforms184t().orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val aiFileAnalyses = (element["aiFileAnalyses"] as? JsonObject)
            ?.mapValues { (_, value) -> value.asStringOrNullTransforms184t().orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val inlineKeyboard = (element["inlineKeyboard"] as? JsonArray)?.mapNotNull { rowEl ->
            val rowArr = rowEl as? JsonArray ?: return@mapNotNull null
            rowArr.mapNotNull { btnEl ->
                val o = btnEl as? JsonObject ?: return@mapNotNull null
                val t = o["text"]?.asStringOrNullTransforms184t().orEmpty()
                val d = o["callbackData"]?.asStringOrNullTransforms184t()
                    ?: o["callback_data"]?.asStringOrNullTransforms184t().orEmpty()
                if (t.isBlank()) null
                else com.maodouchat.core.model.InlineKeyboardButton(text = t.take(64), callbackData = d.take(128))
            }.takeIf { it.isNotEmpty() }
        }?.filter { it.isNotEmpty() } ?: emptyList()
        MessageMeta(
            mentions = mentions,
            replyToId = element["replyToId"]?.asStringOrNullTransforms184t(),
            forwardedFrom = element["forwardedFrom"]?.asStringOrNullTransforms184t(),
            voiceTranscript = element["voiceTranscript"]?.asStringOrNullTransforms184t(),
            voiceDurationMs = element["voiceDurationMs"]?.asStringOrNullTransforms184t()?.toLongOrNull(),
            translations = translations,
            preferredTranslationLanguage = element["preferredTranslationLanguage"]?.asStringOrNullTransforms184t(),
            aiImageAnalyses = aiImageAnalyses,
            preferredImageAnalysisMode = element["preferredImageAnalysisMode"]?.asStringOrNullTransforms184t(),
            aiFileAnalyses = aiFileAnalyses,
            preferredFileAnalysisMode = element["preferredFileAnalysisMode"]?.asStringOrNullTransforms184t(),
            aiFileLastQuestion = element["aiFileLastQuestion"]?.asStringOrNullTransforms184t(),
            aiAssisted = element["aiAssisted"]?.asStringOrNullTransforms184t()?.toBooleanStrictOrNull() ?: false,
            aiAssistantMode = element["aiAssistantMode"]?.asStringOrNullTransforms184t(),
            fileName = element["fileName"]?.asStringOrNullTransforms184t(),
            fileMimeType = element["fileMimeType"]?.asStringOrNullTransforms184t(),
            fileSizeBytes = element["fileSizeBytes"]?.asStringOrNullTransforms184t()?.toLongOrNull(),
            attachmentId = element["attachmentId"]?.asStringOrNullTransforms184t(),
            attachmentKeyBase64 = element["attachmentKeyBase64"]?.asStringOrNullTransforms184t(),
            attachmentIvBase64 = element["attachmentIvBase64"]?.asStringOrNullTransforms184t(),
            attachmentCipherSha256 = element["attachmentCipherSha256"]?.asStringOrNullTransforms184t(),
            attachmentPlainSha256 = element["attachmentPlainSha256"]?.asStringOrNullTransforms184t(),
            attachmentCipherSize = element["attachmentCipherSize"]?.asStringOrNullTransforms184t()?.toLongOrNull(),
            markdown = element["markdown"]?.asStringOrNullTransforms184t()?.toBooleanStrictOrNull() ?: false,
            viewOnce = element["viewOnce"]?.asStringOrNullTransforms184t()?.toBooleanStrictOrNull() ?: false,
            viewOnceOpened = element["viewOnceOpened"]?.asStringOrNullTransforms184t()?.toBooleanStrictOrNull() ?: false,
            silent = element["silent"]?.asStringOrNullTransforms184t()?.toBooleanStrictOrNull() ?: false,
            spoilerMedia = element["spoilerMedia"]?.asStringOrNullTransforms184t()?.toBooleanStrictOrNull() ?: false,
            spoilerRevealed = element["spoilerRevealed"]?.asStringOrNullTransforms184t()?.toBooleanStrictOrNull() ?: false,
            inlineKeyboard = inlineKeyboard,
            forceReply = element["forceReply"]?.asStringOrNullTransforms184t()?.toBooleanStrictOrNull() ?: false,
        )
    }.getOrElse {
        if (json.isBlank()) MessageMeta() else message.meta
    }
}

private val LOCATION_JSON184t = Json { ignoreUnknownKeys = true }

internal fun parsedLocationOracle184t(message: Message): LocationPayload? {
    if (message.type != MessageType.LOCATION) return null
    return runCatching {
        LOCATION_JSON184t.decodeFromString(LocationPayload.serializer(), parsedContentOracle184t(message))
    }.getOrNull()?.takeIf {
        it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0
    }
}

internal fun toRevokedPlaceholderOracle184t(message: Message, placeholder: String): Message = message.copy(
    content = placeholder,
    type = MessageType.REVOKED,
    meta = MessageMeta(),
)

internal fun toOptimisticEditOracle184t(message: Message, newContent: String): Message {
    val previousRevision = maxOf(message.timestamp, message.editedAt ?: message.timestamp)
    val nextRevision = if (previousRevision == Long.MAX_VALUE) Long.MAX_VALUE else previousRevision + 1L
    return message.copy(content = newContent, editedAt = nextRevision)
}

internal fun displayedTranslationOracle184t(meta: MessageMeta): String? {
    val preferred = meta.preferredTranslationLanguage?.let(meta.translations::get)
    return preferred?.takeIf { it.isNotBlank() }
        ?: meta.translations["\u4e2d\u6587"]?.takeIf { it.isNotBlank() }
        ?: meta.translations.values.lastOrNull { it.isNotBlank() }
}

internal fun semanticSearchTextOracle184t(message: Message, maxLength: Int = 700): String {
    val metadata = parsedMetaOracle184t(message)
    return buildList {
        when (message.type) {
            MessageType.TEXT, MessageType.MARKDOWN ->
                parsedContentOracle184t(message).trim().takeIf(String::isNotBlank)?.let(::add)
            MessageType.LOCATION -> {
                parsedLocationOracle184t(message)?.label?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            }
            MessageType.NUDGE ->
                message.content.trim().takeIf(String::isNotBlank)?.let(::add)
            else -> Unit
        }
        metadata.voiceTranscript?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        addAll(metadata.translations.values.map(String::trim).filter(String::isNotBlank))
        addAll(metadata.aiImageAnalyses.values.map(String::trim).filter(String::isNotBlank))
        addAll(metadata.aiFileAnalyses.values.map(String::trim).filter(String::isNotBlank))
        metadata.aiFileLastQuestion?.trim()?.takeIf(String::isNotBlank)?.let(::add)
    }
        .distinct()
        .joinToString("\n")
        .take(maxLength.coerceIn(1, 10_000))
}

internal fun forwardPreviewTextOracle184t(type: MessageType): ChatMessagePreviewText? = when (type) {
    MessageType.IMAGE -> ChatMessagePreviewText.PREVIEW_IMAGE
    MessageType.GIF -> ChatMessagePreviewText.PREVIEW_GIF
    MessageType.STICKER -> ChatMessagePreviewText.PREVIEW_STICKER
    MessageType.LOCATION -> ChatMessagePreviewText.PREVIEW_LOCATION
    MessageType.VIDEO -> ChatMessagePreviewText.PREVIEW_VIDEO
    MessageType.VOICE -> ChatMessagePreviewText.PREVIEW_VOICE
    MessageType.FILE -> ChatMessagePreviewText.PREVIEW_FILE
    else -> null
}

internal fun escJsonTransforms184t(s: String): String {
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

internal fun metaWireJson184t(m: MessageMeta): String {
    val mentions = m.mentions.joinToString(",") { escJsonTransforms184t(it) }
    val translations = m.translations.entries.joinToString(",") { (k, v) -> "${escJsonTransforms184t(k)}:${escJsonTransforms184t(v)}" }
    val aiImage = m.aiImageAnalyses.entries.joinToString(",") { (k, v) -> "${escJsonTransforms184t(k)}:${escJsonTransforms184t(v)}" }
    val aiFile = m.aiFileAnalyses.entries.joinToString(",") { (k, v) -> "${escJsonTransforms184t(k)}:${escJsonTransforms184t(v)}" }
    val keyboard = m.inlineKeyboard.joinToString(",") { row ->
        "[" + row.joinToString(",") { b ->
            "{\"text\":${escJsonTransforms184t(b.text)},\"callbackData\":${escJsonTransforms184t(b.callbackData)}}"
        } + "]"
    }
    fun optS(v: String?): String = v?.let { escJsonTransforms184t(it) } ?: "null"
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

internal fun messageWireJson184t(m: Message): String {
    val reactions = m.reactions.joinToString(",") { r ->
        "{\"userId\":${escJsonTransforms184t(r.userId)},\"emoji\":${escJsonTransforms184t(r.emoji)},\"reactedAt\":${r.reactedAt}}"
    }
    val edited = m.editedAt?.toString() ?: "null"
    val expires = m.expiresAt?.toString() ?: "null"
    return "{\"id\":${escJsonTransforms184t(m.id)},\"chatId\":${escJsonTransforms184t(m.chatId)}," +
        "\"senderId\":${escJsonTransforms184t(m.senderId)},\"content\":${escJsonTransforms184t(m.content)}," +
        "\"type\":${escJsonTransforms184t(m.type.name)},\"timestamp\":${m.timestamp}," +
        "\"status\":${escJsonTransforms184t(m.status.name)},\"editedAt\":$edited," +
        "\"starred\":${m.starred},\"meta\":${escJsonTransforms184t(metaWireJson184t(m.meta))}," +
        "\"reactions\":[$reactions],\"expiresAt\":$expires," +
        "\"sealedSender\":${m.sealedSender}}"
}

// ---------- production ----------

internal fun revokeProduction184t(message: Message, placeholder: String): Message =
    message.toRevokedPlaceholder(placeholder)

internal fun editProduction184t(message: Message, newContent: String): Message =
    message.toOptimisticEdit(newContent)

internal fun displayedProduction184t(meta: MessageMeta): String? =
    meta.displayedTranslation()

internal fun semTextProduction184t(message: Message, maxLength: Int): String =
    message.semanticSearchText(maxLength)

internal fun previewProduction184t(type: MessageType): ChatMessagePreviewText? =
    forwardPreviewText(type)

// ---------- generators ----------

internal val TRANSFORMS_CONTENTS184t: List<String> = listOf(
    "hello world",
    "  padded  ",
    "",
    " ",
    "a &amp; b &#65;&#x42; &lt;x&gt;",
    "line1\nline2",
    "caf\u00e9 r\u00e9sum\u00e9",
    "\uD83D\uDE00 emoji text \uD83D\uDE00",
    "x".repeat(100),
    "prefix <meta> literal in body",
    "{\"latitude\":39.9,\"longitude\":116.4,\"label\":\"Beijing\"}",
    "{\"latitude\":91.0,\"longitude\":0.0,\"label\":\"north\"}",
    "voice memo",
    "same same same",
)

internal val TRANSFORMS_META_JSON184t: List<String> = listOf(
    "{\"voiceTranscript\":\"hello world\",\"translations\":{\"en\":\"hello\",\"zh\":\"\\u4f60\\u597d\"}}",
    "{\"voiceTranscript\":\"  \",\"translations\":{\"en\":\"\",\"fr\":\"bonjour\"}}",
    "{\"translations\":{\"en\":\"hello\",\"en2\":\"hello\"}}",
    "{\"translations\":{\"zh\":\"\\u4e2d\\u6587\",\"en\":\"english\"}}",
    "{\"preferredTranslationLanguage\":\"fr\",\"translations\":{\"fr\":\"\",\"en\":\"english\"}}",
    "{\"preferredTranslationLanguage\":\"zh\",\"translations\":{\"zh\":\"  \",\"en\":\"english\"}}",
    "{\"preferredTranslationLanguage\":\"de\",\"translations\":{\"en\":\"english\"}}",
    "{\"aiImageAnalyses\":{\"a\":\"a cat\",\"b\":\"  \"},\"aiFileAnalyses\":{\"q\":\"answer\"},\"aiFileLastQuestion\":\"what?\"}",
    "{\"aiImageAnalyses\":{\"a\":\"dup\"},\"aiFileAnalyses\":{\"b\":\"dup\"},\"voiceTranscript\":\"dup\"}",
    "{\"mentions\":[\"alice\",42,null]}",
    "{\"voiceDurationMs\":\"1.5\",\"aiAssisted\":\"True\"}",
    "{}",
    "{",
    "{\"translations\":\"en\"}",
    "{\"translations\":{\"en\":123}}",
    "123",
    "[1,2]",
    "{\"fileName\":\"report.pdf\",\"fileMimeType\":\"application/pdf\"}",
)

internal val TRANSFORMS_PLACEHOLDERS184t: List<String> = listOf(
    "", " ", "[已撤回]", "revoked", "  x  ", "\uD83D\uDE00",
)

internal val TRANSFORMS_MAX_LENGTH184t: List<Int> = listOf(
    Int.MIN_VALUE, -1, 0, 1, 2, 5, 10, 700, 9999, 10000, 10001, Int.MAX_VALUE,
)

internal fun randomMetaTransforms184t(rnd: Random): MessageMeta = MessageMeta(
    mentions = List(rnd.nextInt(0, 3)) { listOf("alice", "bob").random(rnd) },
    voiceTranscript = if (rnd.nextBoolean()) null else listOf("hello world", "  ", "", "dup").random(rnd),
    translations = mapOf(
        "en" to listOf("hello", "", "  ", "dup").random(rnd),
        "zh" to listOf("\u4e2d\u6587", "\u4f60\u597d", "").random(rnd),
        "\u4e2d\u6587" to listOf("\u4e2d\u6587", "").random(rnd),
    ).filterValues { rnd.nextBoolean() },
    preferredTranslationLanguage = if (rnd.nextBoolean()) null else listOf("en", "zh", "\u4e2d\u6587", "fr", "de").random(rnd),
    aiImageAnalyses = if (rnd.nextBoolean()) emptyMap() else mapOf("a" to listOf("a cat", "dup", "  ").random(rnd)),
    aiFileAnalyses = if (rnd.nextBoolean()) emptyMap() else mapOf("q" to listOf("answer", "dup").random(rnd)),
    aiFileLastQuestion = if (rnd.nextBoolean()) null else listOf("what?", "dup", "  ").random(rnd),
    fileName = if (rnd.nextBoolean()) null else "report.pdf",
    fileMimeType = if (rnd.nextBoolean()) null else "application/pdf",
)

internal fun randomMessageTransforms184t(rnd: Random): Message {
    val body = TRANSFORMS_CONTENTS184t.random(rnd)
    val r = rnd.nextInt(0, 10)
    val content = when {
        r <= 3 -> body + "<meta>" + TRANSFORMS_META_JSON184t.random(rnd) + "</meta>"
        r == 4 -> body + "<meta>" + TRANSFORMS_META_JSON184t.random(rnd)
        r == 5 -> body + "<meta>" + "{\"latitude\":39.9,\"longitude\":116.4,\"label\":\"Beijing\"}" + "</meta>"
        else -> body
    }
    val type = if (r == 5) MessageType.LOCATION else MessageType.entries.random(rnd)
    return Message(
        id = "m${rnd.nextInt(0, 8)}",
        chatId = "c1",
        senderId = listOf("alice", "bob", "carol").random(rnd),
        content = content,
        type = type,
        timestamp = rnd.nextLong(-1000L, 100000L),
        status = com.maodouchat.core.model.MessageStatus.entries.random(rnd),
        editedAt = listOf(null, Long.MIN_VALUE, -1L, 0L, 1L, 1000L, Long.MAX_VALUE - 1L, Long.MAX_VALUE).random(rnd),
        starred = rnd.nextBoolean(),
        meta = randomMetaTransforms184t(rnd),
    )
}

internal fun msgTransforms184t(
    content: String,
    type: MessageType = MessageType.TEXT,
    timestamp: Long = 1000L,
    editedAt: Long? = null,
    meta: MessageMeta = MessageMeta(),
    id: String = "m1",
): Message = Message(
    id = id, chatId = "c1", senderId = "alice", content = content,
    type = type, timestamp = timestamp, editedAt = editedAt, meta = meta,
)

internal data class RevokeCase184t(val message: Message, val placeholder: String)
internal data class EditCase184t(val message: Message, val newContent: String)
internal data class DisplayedCase184t(val meta: MessageMeta)
internal data class SemTextCase184t(val message: Message, val maxLength: Int)
internal data class PreviewCase184t(val type: MessageType)

internal fun randomRevokeCase184t(rnd: Random): RevokeCase184t =
    RevokeCase184t(randomMessageTransforms184t(rnd), TRANSFORMS_PLACEHOLDERS184t.random(rnd))

internal fun randomEditCase184t(rnd: Random): EditCase184t =
    EditCase184t(randomMessageTransforms184t(rnd), TRANSFORMS_CONTENTS184t.random(rnd))

internal fun randomDisplayedCase184t(rnd: Random): DisplayedCase184t =
    DisplayedCase184t(randomMetaTransforms184t(rnd))

internal fun randomSemTextCase184t(rnd: Random): SemTextCase184t =
    SemTextCase184t(randomMessageTransforms184t(rnd), TRANSFORMS_MAX_LENGTH184t.random(rnd))

internal fun randomPreviewCase184t(rnd: Random): PreviewCase184t =
    PreviewCase184t(MessageType.entries.random(rnd))

internal fun revokeCaseWireJson184t(c: RevokeCase184t): String =
    "{\"message\":${messageWireJson184t(c.message)},\"placeholder\":${escJsonTransforms184t(c.placeholder)}}"

internal fun editCaseWireJson184t(c: EditCase184t): String =
    "{\"message\":${messageWireJson184t(c.message)},\"newContent\":${escJsonTransforms184t(c.newContent)}}"

internal fun displayedCaseWireJson184t(c: DisplayedCase184t): String =
    "{\"meta\":${escJsonTransforms184t(metaWireJson184t(c.meta))}}"

internal fun semTextCaseWireJson184t(c: SemTextCase184t): String =
    "{\"message\":${messageWireJson184t(c.message)},\"maxLength\":${c.maxLength}}"

internal fun previewCaseWireJson184t(c: PreviewCase184t): String =
    "{\"type\":${escJsonTransforms184t(c.type.name)}}"

class MessageTransformsFuzzTest {

    private fun checkRevoke184t(c: RevokeCase184t) {
        assertEquals(
            toRevokedPlaceholderOracle184t(c.message, c.placeholder),
            revokeProduction184t(c.message, c.placeholder),
            "revoke($c)",
        )
    }

    private fun checkEdit184t(c: EditCase184t) {
        assertEquals(
            toOptimisticEditOracle184t(c.message, c.newContent),
            editProduction184t(c.message, c.newContent),
            "edit($c)",
        )
    }

    private fun checkDisplayed184t(c: DisplayedCase184t) {
        assertEquals(
            displayedTranslationOracle184t(c.meta),
            displayedProduction184t(c.meta),
            "displayed($c)",
        )
    }

    private fun checkSemText184t(c: SemTextCase184t) {
        assertEquals(
            semanticSearchTextOracle184t(c.message, c.maxLength),
            semTextProduction184t(c.message, c.maxLength),
            "semtext($c)",
        )
    }

    private fun checkPreview184t(c: PreviewCase184t) {
        assertEquals(
            forwardPreviewTextOracle184t(c.type),
            previewProduction184t(c.type),
            "preview($c)",
        )
    }

    @Test
    fun pinnedMessageTransforms() {
        // Revoke: content + type replaced, meta reset, rest untouched.
        val rich = msgTransforms184t(
            "hello<meta>{\"voiceTranscript\":\"vt\"}</meta>",
            editedAt = 42L,
            meta = MessageMeta(voiceTranscript = "vt", translations = mapOf("en" to "hi")),
        )
        checkRevoke184t(RevokeCase184t(rich, "[已撤回]"))
        checkRevoke184t(RevokeCase184t(rich, ""))
        checkRevoke184t(RevokeCase184t(msgTransforms184t("x", type = MessageType.REVOKED), "again"))
        // Edit: revision is max(timestamp, editedAt) + 1, saturating at MAX.
        checkEdit184t(EditCase184t(msgTransforms184t("a", timestamp = 100L, editedAt = null), "b"))
        checkEdit184t(EditCase184t(msgTransforms184t("a", timestamp = 100L, editedAt = 50L), "b"))
        checkEdit184t(EditCase184t(msgTransforms184t("a", timestamp = 100L, editedAt = 200L), "b"))
        checkEdit184t(EditCase184t(msgTransforms184t("a", timestamp = Long.MAX_VALUE, editedAt = null), "b"))
        checkEdit184t(EditCase184t(msgTransforms184t("a", timestamp = 5L, editedAt = Long.MAX_VALUE), "b"))
        checkEdit184t(EditCase184t(msgTransforms184t("a", timestamp = Long.MAX_VALUE - 1L, editedAt = null), "b"))
        checkEdit184t(EditCase184t(msgTransforms184t("a", timestamp = Long.MIN_VALUE, editedAt = Long.MIN_VALUE), "b"))
        // Displayed: preferred, then Chinese, then last non-blank in order.
        checkDisplayed184t(DisplayedCase184t(MessageMeta(translations = mapOf("en" to "hello", "zh" to "你好"), preferredTranslationLanguage = "en")))
        checkDisplayed184t(DisplayedCase184t(MessageMeta(translations = mapOf("en" to "hello", "zh" to "你好"), preferredTranslationLanguage = "fr")))
        checkDisplayed184t(DisplayedCase184t(MessageMeta(translations = mapOf("en" to "hello", "zh" to "  "), preferredTranslationLanguage = "zh")))
        checkDisplayed184t(DisplayedCase184t(MessageMeta(translations = mapOf("en" to "", "fr" to "bonjour"), preferredTranslationLanguage = "de")))
        checkDisplayed184t(DisplayedCase184t(MessageMeta(translations = mapOf("en" to "", "fr" to "  "))))
        checkDisplayed184t(DisplayedCase184t(MessageMeta(translations = emptyMap(), preferredTranslationLanguage = "en")))
        checkDisplayed184t(DisplayedCase184t(MessageMeta(translations = mapOf("中文" to "中文译文", "en" to "english"))))
        checkDisplayed184t(DisplayedCase184t(MessageMeta(translations = mapOf("en" to "english", "中文" to "  "))))
        // SemText: type branches, dedupe, maxLength clamp.
        checkSemText184t(SemTextCase184t(msgTransforms184t("hello world"), 700))
        checkSemText184t(SemTextCase184t(msgTransforms184t("  hello  "), 700))
        checkSemText184t(SemTextCase184t(msgTransforms184t("hello", type = MessageType.MARKDOWN), 700))
        checkSemText184t(SemTextCase184t(msgTransforms184t("hello", type = MessageType.IMAGE), 700))
        checkSemText184t(SemTextCase184t(msgTransforms184t("{\"latitude\":39.9,\"longitude\":116.4,\"label\":\"Beijing\"}", type = MessageType.LOCATION), 700))
        checkSemText184t(SemTextCase184t(msgTransforms184t("{\"latitude\":91.0,\"longitude\":0.0,\"label\":\"north\"}", type = MessageType.LOCATION), 700))
        checkSemText184t(SemTextCase184t(msgTransforms184t("you nudged", type = MessageType.NUDGE), 700))
        checkSemText184t(SemTextCase184t(msgTransforms184t("dup<meta>{\"voiceTranscript\":\"dup\",\"translations\":{\"en\":\"dup\"}}</meta>"), 700))
        checkSemText184t(SemTextCase184t(msgTransforms184t("a<meta>{\"voiceTranscript\":\"  \",\"translations\":{\"en\":\"\"}}</meta>"), 700))
        for (n in TRANSFORMS_MAX_LENGTH184t) {
            checkSemText184t(SemTextCase184t(msgTransforms184t("hello world this is long"), n))
        }
        // Preview: every type maps (or null).
        for (type in MessageType.entries) {
            checkPreview184t(PreviewCase184t(type))
        }
    }

    @Test
    fun randomMessageTransformsMatchProduction() {
        val rnd = Random(184004)
        repeat(1200) { checkRevoke184t(randomRevokeCase184t(rnd)) }
        repeat(1200) { checkEdit184t(randomEditCase184t(rnd)) }
        repeat(800) { checkDisplayed184t(randomDisplayedCase184t(rnd)) }
        repeat(1500) { checkSemText184t(randomSemTextCase184t(rnd)) }
        repeat(200) { checkPreview184t(randomPreviewCase184t(rnd)) }
    }

    @Test
    fun sweepMessageTransformsBoundaries() {
        // Every type x semtext, every meta JSON variant x semtext.
        for (type in MessageType.entries) {
            checkSemText184t(SemTextCase184t(msgTransforms184t("hello world", type = type), 700))
            checkRevoke184t(RevokeCase184t(msgTransforms184t("hello", type = type), "[已撤回]"))
            checkPreview184t(PreviewCase184t(type))
        }
        for (meta in TRANSFORMS_META_JSON184t) {
            checkSemText184t(SemTextCase184t(msgTransforms184t("body<meta>$meta</meta>"), 700))
            checkSemText184t(SemTextCase184t(msgTransforms184t("body<meta>$meta</meta>", type = MessageType.VOICE), 700))
        }
        // Every placeholder x edit revision edge.
        for (p in TRANSFORMS_PLACEHOLDERS184t) {
            checkRevoke184t(RevokeCase184t(msgTransforms184t("hello"), p))
        }
        for (ts in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE - 1L, Long.MAX_VALUE)) {
            for (ed in listOf<Long?>(null, Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE - 1L, Long.MAX_VALUE)) {
                checkEdit184t(EditCase184t(msgTransforms184t("a", timestamp = ts, editedAt = ed), "b"))
            }
        }
        // Displayed: preferred x translations order.
        val orders = listOf(
            mapOf("en" to "hello", "zh" to "你好"),
            mapOf("zh" to "你好", "en" to "hello"),
            mapOf("en" to "", "fr" to "bonjour", "zh" to "你好"),
            mapOf("fr" to "bonjour", "en" to ""),
        )
        for (tr in orders) {
            for (pref in listOf<String?>(null, "en", "zh", "fr", "de", "中文")) {
                checkDisplayed184t(DisplayedCase184t(MessageMeta(translations = tr, preferredTranslationLanguage = pref)))
            }
        }
    }
}
