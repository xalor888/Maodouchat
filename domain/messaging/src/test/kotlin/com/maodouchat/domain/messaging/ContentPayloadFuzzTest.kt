package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageStatus
import com.maodouchat.core.model.MessageType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// Content payload codec restated by hand. Ground truth, read from
// ContentPayloadCodec.kt / Message.kt / MessageMetaJson.kt /
// MessageJsonPolicy.kt / ChatTextDisplayPolicy.kt:
//   - parsedContent: body before the LAST "<meta>", HTML entities
//     unescaped; no marker means the whole content.
//   - parsedMeta: meta JSON after the last "<meta>" (up to "</meta>");
//     blank JSON means defaults, bad JSON / non-object means the
//     message's own meta field.
//   - decodeLegacyBody: same marker rules, but a missing "</meta>"
//     means the WHOLE body is kept (unescaped); metadata parse
//     failure means defaults (never the message meta).
//   - fromLegacyMessage: body from decodeLegacyBody, metadata from
//     parsedMeta; reply/attachments/mentions derived from that meta.
//   - normalizeLocalMessage: content := parsedContent, meta :=
//     parsedMeta, everything else untouched.
//   - toJsonElement: null -> JsonNull, JsonElement -> itself,
//     MessageMeta -> its 33-field map, Map -> JsonObject (keys via
//     toString), List -> JsonArray, Number / Boolean -> JsonPrimitive,
//     anything else -> JsonPrimitive(value.toString()).
internal fun unescapeHtmlEntitiesOracle185(raw: String): String {
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

internal fun parsedContentOracle185(message: Message): String {
    val idx = message.content.lastIndexOf("<meta>")
    val body = if (idx < 0) message.content else message.content.substring(0, idx)
    return unescapeHtmlEntitiesOracle185(body)
}

private val META_JSON185 = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }

internal fun JsonElement.asStringOrNullPayload185(): String? =
    if (this is JsonPrimitive) contentOrNull else null

internal fun parseMetaJsonOracle185(text: String): MessageMeta {
    if (text.isBlank()) return MessageMeta()
    val element = META_JSON185.parseToJsonElement(text).jsonObject
    val mentions = (element["mentions"] as? JsonArray)
        ?.mapNotNull { it.asStringOrNullPayload185() } ?: emptyList()
    val translations = (element["translations"] as? JsonObject)
        ?.mapValues { (_, value) -> value.asStringOrNullPayload185().orEmpty() }
        ?.filterValues { it.isNotBlank() }
        .orEmpty()
    val aiImageAnalyses = (element["aiImageAnalyses"] as? JsonObject)
        ?.mapValues { (_, value) -> value.asStringOrNullPayload185().orEmpty() }
        ?.filterValues { it.isNotBlank() }
        .orEmpty()
    val aiFileAnalyses = (element["aiFileAnalyses"] as? JsonObject)
        ?.mapValues { (_, value) -> value.asStringOrNullPayload185().orEmpty() }
        ?.filterValues { it.isNotBlank() }
        .orEmpty()
    val inlineKeyboard = (element["inlineKeyboard"] as? JsonArray)?.mapNotNull { rowEl ->
        val rowArr = rowEl as? JsonArray ?: return@mapNotNull null
        rowArr.mapNotNull { btnEl ->
            val o = btnEl as? JsonObject ?: return@mapNotNull null
            val t = o["text"]?.asStringOrNullPayload185().orEmpty()
            val d = o["callbackData"]?.asStringOrNullPayload185()
                ?: o["callback_data"]?.asStringOrNullPayload185().orEmpty()
            if (t.isBlank()) null
            else com.maodouchat.core.model.InlineKeyboardButton(text = t.take(64), callbackData = d.take(128))
        }.takeIf { it.isNotEmpty() }
    }?.filter { it.isNotEmpty() } ?: emptyList()
    return MessageMeta(
        mentions = mentions,
        replyToId = element["replyToId"]?.asStringOrNullPayload185(),
        forwardedFrom = element["forwardedFrom"]?.asStringOrNullPayload185(),
        voiceTranscript = element["voiceTranscript"]?.asStringOrNullPayload185(),
        voiceDurationMs = element["voiceDurationMs"]?.asStringOrNullPayload185()?.toLongOrNull(),
        translations = translations,
        preferredTranslationLanguage = element["preferredTranslationLanguage"]?.asStringOrNullPayload185(),
        aiImageAnalyses = aiImageAnalyses,
        preferredImageAnalysisMode = element["preferredImageAnalysisMode"]?.asStringOrNullPayload185(),
        aiFileAnalyses = aiFileAnalyses,
        preferredFileAnalysisMode = element["preferredFileAnalysisMode"]?.asStringOrNullPayload185(),
        aiFileLastQuestion = element["aiFileLastQuestion"]?.asStringOrNullPayload185(),
        aiAssisted = element["aiAssisted"]?.asStringOrNullPayload185()?.toBooleanStrictOrNull() ?: false,
        aiAssistantMode = element["aiAssistantMode"]?.asStringOrNullPayload185(),
        fileName = element["fileName"]?.asStringOrNullPayload185(),
        fileMimeType = element["fileMimeType"]?.asStringOrNullPayload185(),
        fileSizeBytes = element["fileSizeBytes"]?.asStringOrNullPayload185()?.toLongOrNull(),
        attachmentId = element["attachmentId"]?.asStringOrNullPayload185(),
        attachmentKeyBase64 = element["attachmentKeyBase64"]?.asStringOrNullPayload185(),
        attachmentIvBase64 = element["attachmentIvBase64"]?.asStringOrNullPayload185(),
        attachmentCipherSha256 = element["attachmentCipherSha256"]?.asStringOrNullPayload185(),
        attachmentPlainSha256 = element["attachmentPlainSha256"]?.asStringOrNullPayload185(),
        attachmentCipherSize = element["attachmentCipherSize"]?.asStringOrNullPayload185()?.toLongOrNull(),
        markdown = element["markdown"]?.asStringOrNullPayload185()?.toBooleanStrictOrNull() ?: false,
        viewOnce = element["viewOnce"]?.asStringOrNullPayload185()?.toBooleanStrictOrNull() ?: false,
        viewOnceOpened = element["viewOnceOpened"]?.asStringOrNullPayload185()?.toBooleanStrictOrNull() ?: false,
        silent = element["silent"]?.asStringOrNullPayload185()?.toBooleanStrictOrNull() ?: false,
        spoilerMedia = element["spoilerMedia"]?.asStringOrNullPayload185()?.toBooleanStrictOrNull() ?: false,
        spoilerRevealed = element["spoilerRevealed"]?.asStringOrNullPayload185()?.toBooleanStrictOrNull() ?: false,
        inlineKeyboard = inlineKeyboard,
        forceReply = element["forceReply"]?.asStringOrNullPayload185()?.toBooleanStrictOrNull() ?: false,
    )
}

internal fun parsedMetaOracle185(message: Message): MessageMeta {
    val idx = message.content.lastIndexOf("<meta>")
    if (idx < 0) return message.meta
    val json = message.content.substring(idx + "<meta>".length).substringBefore("</meta>")
    return runCatching { parseMetaJsonOracle185(json) }.getOrElse { message.meta }
}

internal fun decodeLegacyBodyOracle185(rawBody: String): LegacyBody {
    val index = rawBody.lastIndexOf("<meta>")
    if (index < 0) return LegacyBody(unescapeHtmlEntitiesOracle185(rawBody), MessageMeta())
    val suffixIndex = rawBody.indexOf("</meta>", index + "<meta>".length)
    if (suffixIndex < 0) return LegacyBody(unescapeHtmlEntitiesOracle185(rawBody), MessageMeta())
    val encoded = rawBody.substring(index + "<meta>".length, suffixIndex)
    val metadata = runCatching { parseMetaJsonOracle185(encoded) }.getOrDefault(MessageMeta())
    return LegacyBody(
        body = unescapeHtmlEntitiesOracle185(rawBody.substring(0, index)),
        metadata = metadata,
    )
}

internal fun fromLegacyMessageOracle185(
    message: Message,
    transportBody: String,
    transportType: MessageType,
): DecodedContentPayload {
    val metadata = parsedMetaOracle185(message)
    return DecodedContentPayload(
        type = transportType,
        body = decodeLegacyBodyOracle185(transportBody).body,
        metadata = metadata,
        replyToMessageId = metadata.replyToId,
        attachmentIds = metadata.attachmentId?.let(::listOf) ?: emptyList(),
        mentionedUserIds = metadata.mentions,
    )
}

internal fun normalizeLocalMessageOracle185(message: Message): Message = message.copy(
    content = parsedContentOracle185(message),
    meta = parsedMetaOracle185(message),
)

internal fun messageMetaMapOracle185(meta: MessageMeta): Map<String, Any?> = mapOf(
    "mentions" to meta.mentions,
    "replyToId" to meta.replyToId,
    "forwardedFrom" to meta.forwardedFrom,
    "voiceTranscript" to meta.voiceTranscript,
    "voiceDurationMs" to meta.voiceDurationMs,
    "translations" to meta.translations,
    "preferredTranslationLanguage" to meta.preferredTranslationLanguage,
    "aiImageAnalyses" to meta.aiImageAnalyses,
    "preferredImageAnalysisMode" to meta.preferredImageAnalysisMode,
    "aiFileAnalyses" to meta.aiFileAnalyses,
    "preferredFileAnalysisMode" to meta.preferredFileAnalysisMode,
    "aiFileLastQuestion" to meta.aiFileLastQuestion,
    "aiAssisted" to meta.aiAssisted,
    "aiAssistantMode" to meta.aiAssistantMode,
    "fileName" to meta.fileName,
    "fileMimeType" to meta.fileMimeType,
    "fileSizeBytes" to meta.fileSizeBytes,
    "attachmentId" to meta.attachmentId,
    "attachmentKeyBase64" to meta.attachmentKeyBase64,
    "attachmentIvBase64" to meta.attachmentIvBase64,
    "attachmentCipherSha256" to meta.attachmentCipherSha256,
    "attachmentPlainSha256" to meta.attachmentPlainSha256,
    "attachmentCipherSize" to meta.attachmentCipherSize,
    "markdown" to meta.markdown,
    "viewOnce" to meta.viewOnce,
    "viewOnceOpened" to meta.viewOnceOpened,
    "silent" to meta.silent,
    "spoilerMedia" to meta.spoilerMedia,
    "spoilerRevealed" to meta.spoilerRevealed,
    "inlineKeyboard" to meta.inlineKeyboard.map { row ->
        row.map { btn -> mapOf("text" to btn.text, "callbackData" to btn.callbackData) }
    },
    "forceReply" to meta.forceReply,
)

internal fun toJsonElementOracle185(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is MessageMeta -> toJsonElementOracle185(messageMetaMapOracle185(value))
    is Map<*, *> -> JsonObject(
        value.entries.associate { (k, v) -> k.toString() to toJsonElementOracle185(v) }
    )
    is List<*> -> JsonArray(value.map { toJsonElementOracle185(it) })
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

// org.json-free conversion: JsonElement -> Any? for the toJsonElement
// wire cases. Numbers become Long when they fit, else Double.
internal fun jsonElementToAny185(el: JsonElement): Any? = when (el) {
    is JsonNull -> null
    is JsonPrimitive -> if (el.isString) el.content
        else when (el.content) {
            "true" -> true
            "false" -> false
            else -> el.content.toLongOrNull() ?: el.content.toDoubleOrNull()
        }
    is JsonArray -> el.map { jsonElementToAny185(it) }
    is JsonObject -> el.entries.associate { (k, v) -> k to jsonElementToAny185(v) }
}

internal fun escJsonPayload185(s: String): String {
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

internal fun metaWireJson185(m: MessageMeta): String {
    val mentions = m.mentions.joinToString(",") { escJsonPayload185(it) }
    val translations = m.translations.entries.joinToString(",") { (k, v) -> "${escJsonPayload185(k)}:${escJsonPayload185(v)}" }
    val aiImage = m.aiImageAnalyses.entries.joinToString(",") { (k, v) -> "${escJsonPayload185(k)}:${escJsonPayload185(v)}" }
    val aiFile = m.aiFileAnalyses.entries.joinToString(",") { (k, v) -> "${escJsonPayload185(k)}:${escJsonPayload185(v)}" }
    val keyboard = m.inlineKeyboard.joinToString(",") { row ->
        "[" + row.joinToString(",") { b ->
            "{\"text\":${escJsonPayload185(b.text)},\"callbackData\":${escJsonPayload185(b.callbackData)}}"
        } + "]"
    }
    fun optS(v: String?): String = v?.let { escJsonPayload185(it) } ?: "null"
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

internal fun messageWireJson185(m: Message): String {
    val reactions = m.reactions.joinToString(",") { r ->
        "{\"userId\":${escJsonPayload185(r.userId)},\"emoji\":${escJsonPayload185(r.emoji)},\"reactedAt\":${r.reactedAt}}"
    }
    val edited = m.editedAt?.toString() ?: "null"
    val expires = m.expiresAt?.toString() ?: "null"
    return "{\"id\":${escJsonPayload185(m.id)},\"chatId\":${escJsonPayload185(m.chatId)}," +
        "\"senderId\":${escJsonPayload185(m.senderId)},\"content\":${escJsonPayload185(m.content)}," +
        "\"type\":${escJsonPayload185(m.type.name)},\"timestamp\":${m.timestamp}," +
        "\"status\":${escJsonPayload185(m.status.name)},\"editedAt\":$edited," +
        "\"starred\":${m.starred},\"meta\":${escJsonPayload185(metaWireJson185(m.meta))}," +
        "\"reactions\":[$reactions],\"expiresAt\":$expires," +
        "\"sealedSender\":${m.sealedSender}}"
}

internal fun decodedPayloadWireJson185(p: DecodedContentPayload): String =
    "{\"type\":${escJsonPayload185(p.type.name)}," +
        "\"body\":${escJsonPayload185(p.body)}," +
        "\"metadata\":${escJsonPayload185(metaWireJson185(p.metadata))}," +
        "\"replyToMessageId\":${p.replyToMessageId?.let { escJsonPayload185(it) } ?: "null"}," +
        "\"attachmentIds\":[" + p.attachmentIds.joinToString(",") { escJsonPayload185(it) } + "]," +
        "\"mentionedUserIds\":[" + p.mentionedUserIds.joinToString(",") { escJsonPayload185(it) } + "]," +
        "\"expiresInSeconds\":${p.expiresInSeconds}," +
        "\"attributes\":{" + p.attributes.entries.joinToString(",") { (k, v) -> "${escJsonPayload185(k)}:${escJsonPayload185(v)}" } + "}}"

// ---------- production ----------

internal fun fromLegacyMessageProduction185(
    message: Message,
    transportBody: String?,
    transportType: MessageType?,
): DecodedContentPayload = when {
    transportBody == null && transportType == null -> ContentPayloadCodec.fromLegacyMessage(message)
    transportBody == null -> ContentPayloadCodec.fromLegacyMessage(message, transportType = transportType!!)
    transportType == null -> ContentPayloadCodec.fromLegacyMessage(message, transportBody = transportBody)
    else -> ContentPayloadCodec.fromLegacyMessage(message, transportBody, transportType)
}

internal fun normalizeLocalMessageProduction185(message: Message): Message =
    ContentPayloadCodec.normalizeLocalMessage(message)

internal fun toJsonElementProduction185(value: Any?): JsonElement =
    MessageJsonPolicy.toJsonElement(value)

// ---------- generators ----------

internal val LEGACY_CONTENTS185: List<String> = listOf(
    "hello world",
    "  padded  ",
    "",
    " ",
    "a &amp; b &#65;&#x42; &lt;x&gt;",
    "line1\nline2",
    "café résumé",
    "\uD83D\uDE00 emoji text \uD83D\uDE00",
    "x".repeat(100),
    "prefix <meta> literal in body",
    "{\"latitude\":39.9,\"longitude\":116.4,\"label\":\"Beijing\"}",
    "voice memo",
    "same same same",
    "body<meta>{\"replyToId\":\"m9\",\"mentions\":[\"alice\"]}</meta>",
    "body<meta>{\"replyToId\":\"m9\",\"attachmentId\":\"att1\"}</meta>",
    "body<meta>{bad</meta>",
    "body<meta></meta>",
    "body<meta>   </meta>",
    "body<meta>123</meta>",
    "body<meta>\"str\"</meta>",
    "body<meta>[1,2]</meta>",
    "body<meta>{\"replyToId\":42}</meta>",
    "body<meta>{\"mentions\":\"alice\"}</meta>",
    "first <meta> literal <meta>{\"replyToId\":\"m2\"}</meta>",
    "a<meta>b</meta>c<meta>{\"replyToId\":\"m3\"}</meta>",
    "a<meta>{\"replyToId\":\"m4\"}",
    "a</meta> tail",
)

internal val LEGACY_BODIES185: List<String> = listOf(
    "hello",
    "hello<meta>{\"replyToId\":\"m9\"}</meta>",
    "hello<meta>{\"replyToId\":\"m9\"}",
    "hello<meta>no-json</meta>",
    "hello<meta>{\"mentions\":[\"bob\"]}</meta> trailing",
    "a &amp; b",
    "x<meta>{\"attachmentId\":\"att1\",\"mentions\":[\"a\",\"b\"]}</meta>",
    "x<meta></meta>",
    "x<meta>   </meta>",
    "x<meta>123</meta>",
    "x<meta>{\"replyToId\":\"m1\"}</meta>y<meta>{\"replyToId\":\"m2\"}</meta>",
    "x<meta>{\"replyToId\":\"m1\"}</meta>y",
    "<meta>{\"replyToId\":\"m5\"}</meta>",
    "  spaced <meta>{\"replyToId\":\"m6\"}</meta>  ",
    "a<meta>b</meta>",
    "a<meta>b",
    "a</meta>b<meta>{\"replyToId\":\"m7\"}</meta>",
    "&#65;&#x42; &lt;tag&gt;",
)

internal val TOJSON_SCALARS185: List<String> = listOf(
    "null", "true", "false", "0", "1", "-1", "42",
    "9223372036854775807", "-9223372036854775808",
    "1.0", "0.5", "-0.0", "3.14", "2.5", "-7.25", "1000.0", "0.001",
    "1.0E10", "1.0E-4", "123456.789",
    "\"\"", "\"a\"", "\"hello world\"", "\"café\"", "\"a\\nb\"",
    "\"a\\\"b\"", "\"\\\\\"", "\"a/b\"", "\"\\u4f60\\u597d\"",
    "\"\\u0001\"", "\"x\\ty\"",
)

internal val TOJSON_KEYS185: List<String> = listOf(
    "a", "b", "key", "x", "1", "true", "null", "a b", "é", "",
)

internal val LEGACY_META_JSON185: List<String> = listOf(
    "{\"voiceTranscript\":\"hello world\",\"translations\":{\"en\":\"hello\",\"zh\":\"\\u4f60\\u597d\"}}",
    "{\"voiceTranscript\":\"  \",\"translations\":{\"en\":\"\",\"fr\":\"bonjour\"}}",
    "{\"replyToId\":\"m9\",\"mentions\":[\"alice\",42,null]}",
    "{\"attachmentId\":\"att1\",\"mentions\":[\"a\",\"b\"]}",
    "{\"voiceDurationMs\":\"1.5\",\"aiAssisted\":\"True\"}",
    "{}",
    "{",
    "{\"translations\":\"en\"}",
    "{\"translations\":{\"en\":123}}",
    "123",
    "[1,2]",
    "{\"fileName\":\"report.pdf\",\"fileMimeType\":\"application/pdf\"}",
    "{\"inlineKeyboard\":[[{\"text\":\"ok\",\"callbackData\":\"cb\"}]]}",
)

internal fun randomMetaPayload185(rnd: Random): MessageMeta = MessageMeta(
    mentions = List(rnd.nextInt(0, 3)) { listOf("alice", "bob").random(rnd) },
    replyToId = if (rnd.nextBoolean()) null else "m${rnd.nextInt(0, 9)}",
    voiceTranscript = if (rnd.nextBoolean()) null else listOf("hello world", "  ", "", "dup").random(rnd),
    translations = mapOf(
        "en" to listOf("hello", "", "  ", "dup").random(rnd),
        "zh" to listOf("\u4e2d\u6587", "\u4f60\u597d", "").random(rnd),
    ).filterValues { rnd.nextBoolean() },
    preferredTranslationLanguage = if (rnd.nextBoolean()) null else listOf("en", "zh", "fr").random(rnd),
    attachmentId = if (rnd.nextBoolean()) null else "att${rnd.nextInt(0, 9)}",
    fileName = if (rnd.nextBoolean()) null else "report.pdf",
    fileMimeType = if (rnd.nextBoolean()) null else "application/pdf",
    aiAssisted = rnd.nextBoolean(),
    markdown = rnd.nextBoolean(),
)

internal fun randomMessagePayload185(rnd: Random): Message {
    val body = LEGACY_CONTENTS185.random(rnd)
    val r = rnd.nextInt(0, 10)
    val content = when {
        r <= 3 -> body + "<meta>" + LEGACY_META_JSON185.random(rnd) + "</meta>"
        r == 4 -> body + "<meta>" + LEGACY_META_JSON185.random(rnd)
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
        status = MessageStatus.entries.random(rnd),
        editedAt = listOf(null, Long.MIN_VALUE, -1L, 0L, 1L, 1000L, Long.MAX_VALUE - 1L, Long.MAX_VALUE).random(rnd),
        starred = rnd.nextBoolean(),
        meta = randomMetaPayload185(rnd),
    )
}

internal fun msgPayload185(
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

internal data class LegacyCase185(
    val message: Message,
    val transportBody: String?,
    val transportType: MessageType?,
)

internal data class NormalizeCase185(val message: Message)

internal data class ToJsonCase185(
    val kind: String,
    val valueText: String?,
    val meta: MessageMeta?,
    val pairs: List<Pair<String, String>>?,
)

internal fun randomLegacyCase185(rnd: Random): LegacyCase185 {
    val message = randomMessagePayload185(rnd)
    return LegacyCase185(
        message = message,
        transportBody = when (rnd.nextInt(0, 3)) {
            0 -> null
            1 -> parsedContentOracle185(message)
            else -> LEGACY_BODIES185.random(rnd)
        },
        transportType = when (rnd.nextInt(0, 3)) {
            0 -> null
            1 -> message.type
            else -> MessageType.entries.random(rnd)
        },
    )
}

internal fun randomNormalizeCase185(rnd: Random): NormalizeCase185 =
    NormalizeCase185(randomMessagePayload185(rnd))

internal fun randomJsonText185(rnd: Random, depth: Int): String {
    if (depth <= 0) return TOJSON_SCALARS185.random(rnd)
    return when (rnd.nextInt(0, 5)) {
        0, 3, 4 -> TOJSON_SCALARS185.random(rnd)
        1 -> "[" + List(rnd.nextInt(0, 4)) { randomJsonText185(rnd, depth - 1) }.joinToString(",") + "]"
        else -> "{" + List(rnd.nextInt(0, 4)) {
            "\"${TOJSON_KEYS185.random(rnd)}\":${randomJsonText185(rnd, depth - 1)}"
        }.joinToString(",") + "}"
    }
}

internal fun randomToJsonCase185(rnd: Random): ToJsonCase185 = when (rnd.nextInt(0, 4)) {
    0 -> ToJsonCase185("json", randomJsonText185(rnd, 3), null, null)
    1 -> ToJsonCase185("meta", null, randomMetaPayload185(rnd), null)
    2 -> ToJsonCase185("element", randomJsonText185(rnd, 3), null, null)
    else -> ToJsonCase185(
        "pairs", null, null,
        List(rnd.nextInt(1, 5)) {
            val key = listOf("null", "true", "false", "0", "1", "42", "1.5", "\"a\"", "\"1\"", "\"\"").random(rnd)
            key to randomJsonText185(rnd, 2)
        },
    )
}

internal fun toJsonValue185(c: ToJsonCase185): Any? = when (c.kind) {
    "json" -> jsonElementToAny185(META_JSON185.parseToJsonElement(c.valueText!!))
    "meta" -> c.meta!!
    "element" -> META_JSON185.parseToJsonElement(c.valueText!!)
    else -> c.pairs!!.associate { (k, v) ->
        jsonElementToAny185(META_JSON185.parseToJsonElement(k)) to
            jsonElementToAny185(META_JSON185.parseToJsonElement(v))
    }
}

internal fun legacyCaseWireJson185(c: LegacyCase185): String =
    "{\"message\":${messageWireJson185(c.message)}," +
        "\"transportBody\":${c.transportBody?.let { escJsonPayload185(it) } ?: "null"}," +
        "\"transportType\":${c.transportType?.let { escJsonPayload185(it.name) } ?: "null"}}"

internal fun normalizeCaseWireJson185(c: NormalizeCase185): String =
    "{\"message\":${messageWireJson185(c.message)}}"

internal fun toJsonCaseWireJson185(c: ToJsonCase185): String =
    "{\"kind\":${escJsonPayload185(c.kind)}," +
        "\"valueText\":${c.valueText?.let { escJsonPayload185(it) } ?: "null"}," +
        "\"meta\":${c.meta?.let { escJsonPayload185(metaWireJson185(it)) } ?: "null"}," +
        "\"pairs\":${c.pairs?.let { "[" + it.joinToString(",") { (k, v) -> "[${escJsonPayload185(k)},${escJsonPayload185(v)}]" } + "]" } ?: "null"}}"

class ContentPayloadFuzzTest {

    private fun checkLegacy185(c: LegacyCase185) {
        val body = c.transportBody ?: parsedContentOracle185(c.message)
        val type = c.transportType ?: c.message.type
        assertEquals(
            fromLegacyMessageOracle185(c.message, body, type),
            fromLegacyMessageProduction185(c.message, c.transportBody, c.transportType),
            "legacy($c)",
        )
    }

    private fun checkNormalize185(c: NormalizeCase185) {
        assertEquals(
            normalizeLocalMessageOracle185(c.message),
            normalizeLocalMessageProduction185(c.message),
            "normalize($c)",
        )
    }

    private fun checkToJson185(c: ToJsonCase185) {
        val value = toJsonValue185(c)
        assertEquals(
            toJsonElementOracle185(value),
            toJsonElementProduction185(value),
            "tojson($c)",
        )
    }

    @Test
    fun pinnedContentPayload() {
        // Legacy: body from the transport body, meta from the message.
        val rich = msgPayload185(
            "hello<meta>{\"replyToId\":\"m9\",\"mentions\":[\"alice\"]}</meta>",
            meta = MessageMeta(replyToId = "m9", mentions = listOf("alice")),
        )
        checkLegacy185(LegacyCase185(rich, null, null))
        checkLegacy185(LegacyCase185(rich, "explicit body", MessageType.IMAGE))
        checkLegacy185(LegacyCase185(rich, "x<meta>{\"replyToId\":\"m1\"}</meta>", null))
        checkLegacy185(LegacyCase185(msgPayload185("plain"), "a<meta>b</meta>", MessageType.TEXT))
        checkLegacy185(LegacyCase185(msgPayload185("plain"), "a<meta>b", MessageType.TEXT))
        checkLegacy185(LegacyCase185(msgPayload185("plain"), "a</meta>b", MessageType.TEXT))
        // Normalize: content/meta unpacked, rest untouched.
        for (content in LEGACY_CONTENTS185) {
            checkNormalize185(NormalizeCase185(msgPayload185(content)))
            checkNormalize185(NormalizeCase185(msgPayload185(content, type = MessageType.LOCATION)))
        }
        // toJson: every scalar kind, nesting, meta, pairs with odd keys.
        for (scalar in TOJSON_SCALARS185) {
            checkToJson185(ToJsonCase185("json", scalar, null, null))
            checkToJson185(ToJsonCase185("element", scalar, null, null))
        }
        checkToJson185(ToJsonCase185("json", "{\"a\":1,\"b\":[true,null]}", null, null))
        checkToJson185(ToJsonCase185("json", "[1,\"x\",{\"k\":2.5}]", null, null))
        checkToJson185(ToJsonCase185("json", "{\"a\":{\"b\":{\"c\":[]}}}", null, null))
        checkToJson185(ToJsonCase185("element", "{\"a\":1,\"b\":[true,null]}", null, null))
        checkToJson185(ToJsonCase185("meta", null, MessageMeta(), null))
        checkToJson185(ToJsonCase185("meta", null, randomMetaPayload185(Random(7)), null))
        checkToJson185(ToJsonCase185("pairs", null, null, listOf("1" to "\"a\"", "\"1\"" to "2")))
        checkToJson185(ToJsonCase185("pairs", null, null, listOf("null" to "1", "true" to "false")))
        checkToJson185(ToJsonCase185("pairs", null, null, listOf("1.5" to "null", "\"a\"" to "[1]")))
        // Typed-key collisions: Long 1 and String "1" are different
        // map keys first, then toString() collapses them (later wins).
        checkToJson185(ToJsonCase185("pairs", null, null, listOf("1" to "\"a\"", "\"1\"" to "\"b\"")))
        checkToJson185(ToJsonCase185("pairs", null, null, listOf("\"1\"" to "0", "1" to "1")))
    }

    @Test
    fun randomContentPayloadMatchProduction() {
        val rnd = Random(185003)
        repeat(2000) { checkLegacy185(randomLegacyCase185(rnd)) }
        repeat(1500) { checkNormalize185(randomNormalizeCase185(rnd)) }
        repeat(2500) { checkToJson185(randomToJsonCase185(rnd)) }
    }

    @Test
    fun sweepContentPayloadBoundaries() {
        // Every meta JSON variant through legacy + normalize.
        for (meta in LEGACY_META_JSON185) {
            checkLegacy185(LegacyCase185(msgPayload185("body<meta>$meta</meta>"), null, null))
            checkNormalize185(NormalizeCase185(msgPayload185("body<meta>$meta</meta>")))
            checkNormalize185(NormalizeCase185(msgPayload185("body<meta>$meta</meta>", type = MessageType.VOICE)))
        }
        // Every transport body through legacy, with and without a type override.
        for (body in LEGACY_BODIES185) {
            checkLegacy185(LegacyCase185(msgPayload185("plain"), body, null))
            checkLegacy185(LegacyCase185(msgPayload185("plain"), body, MessageType.FILE))
        }
        // Every message type through legacy + normalize.
        for (type in MessageType.entries) {
            checkLegacy185(LegacyCase185(msgPayload185("hello", type = type), null, null))
            checkNormalize185(NormalizeCase185(msgPayload185("hello", type = type)))
        }
        // toJson: every scalar x every nesting depth shape.
        for (scalar in TOJSON_SCALARS185) {
            checkToJson185(ToJsonCase185("json", "[$scalar]", null, null))
            checkToJson185(ToJsonCase185("json", "{\"k\":$scalar}", null, null))
            checkToJson185(ToJsonCase185("element", "[$scalar]", null, null))
        }
    }
}
