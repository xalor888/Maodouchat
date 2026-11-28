// Fuzz / random-oracle tests for MessagePersistencePolicy (checklist line
// 931, 152nd block). Test-only, zero production changes. The oracle is
// hand-written from the production source: Room write-path merge
// (REVOKED rules, editedAt order, readable-plaintext preference,
// wire-over-placeholder, delivery-status ladder, sealedSender OR,
// view-once/spoiler flag OR through the <meta> tag). Meta JSON
// parsing/encoding is reimplemented from MessageMetaJson field by
// field; only the kotlinx JSON library primitives are shared.
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.InlineKeyboardButton
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageReaction
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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------- oracle ----------

internal val BLANK_UNITS_ORACLE152 = setOf(
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20,
    0xA0, 0x1680,
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007,
    0x2008, 0x2009, 0x200A,
    0x2028, 0x2029, 0x202F, 0x205F, 0x3000
)

internal fun isBlankOracle152(s: String): Boolean = s.all { it.code in BLANK_UNITS_ORACLE152 }

internal fun trimOracle152(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && s[start].code in BLANK_UNITS_ORACLE152) start++
    while (end > start && s[end - 1].code in BLANK_UNITS_ORACLE152) end--
    return s.substring(start, end)
}

internal fun trimStartOracle152(s: String): String {
    var start = 0
    while (start < s.length && s[start].code in BLANK_UNITS_ORACLE152) start++
    return s.substring(start)
}

internal val LEGACY_PLACEHOLDERS152 = setOf(
    "无法解密", "解密失败", "群密钥缺失", "密钥缺失", "decrypt failed",
    "session missing", "identity changed", "[无法解密的消息]",
    "[无法解密的群聊消息]", "[加密消息]", "[缺少会话密钥，请等待对方重新发送]",
    "[对方安全码已变化，请验证身份]", "[群成员安全码已变化，请验证身份]",
    "[缺少群聊 sender key，请等待成员重新发送]",
    "[群聊密钥 epoch 较新，请刷新群信息]", "[无法解密的图片]",
    "[无法解密的 gif]", "[无法解密的贴纸]", "[无法解密的位置]",
    "[无法解密的视频]", "[无法解密的语音]", "[无法解密的文件]",
    "[unable to decrypt message]", "[unable to decrypt group message]",
    "[encrypted message]", "[missing session key. waiting for resend]",
    "[safety number changed. verify identity]",
    "[member safety number changed. verify identity]",
    "[missing group sender key. waiting for redistribution]",
    "[newer group key epoch. refresh group info]",
    "[unable to decrypt image]", "[unable to decrypt gif]",
    "[unable to decrypt sticker]", "[unable to decrypt location]",
    "[unable to decrypt video]", "[unable to decrypt voice message]",
    "[unable to decrypt file]",
)

internal fun unescapeHtmlEntitiesOracle152(raw: String): String {
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

internal fun parsedContentOracle152(content: String): String =
    content.lastIndexOf(Message.META_TAG_PREFIX).let { idx ->
        val body = if (idx < 0) content else content.substring(0, idx)
        unescapeHtmlEntitiesOracle152(body)
    }

internal fun JsonElement.asStringOrNull152(): String? =
    if (this is JsonPrimitive) contentOrNull else null

internal val META_JSON152 = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}

internal fun fromJsonStringOracle152(text: String): MessageMeta {
    if (text.isBlank()) return MessageMeta()
    val element = META_JSON152.parseToJsonElement(text).jsonObject
    val mentions = (element["mentions"] as? JsonArray)
        ?.mapNotNull { it.asStringOrNull152() } ?: emptyList()
    val replyToId = element["replyToId"]?.asStringOrNull152()
    val forwardedFrom = element["forwardedFrom"]?.asStringOrNull152()
    val voiceTranscript = element["voiceTranscript"]?.asStringOrNull152()
    val voiceDurationMs = element["voiceDurationMs"]?.asStringOrNull152()?.toLongOrNull()
    val translations = (element["translations"] as? JsonObject)
        ?.mapValues { (_, value) -> value.asStringOrNull152().orEmpty() }
        ?.filterValues { it.isNotBlank() }
        .orEmpty()
    val preferredTranslationLanguage = element["preferredTranslationLanguage"]
        ?.asStringOrNull152()
    val aiImageAnalyses = (element["aiImageAnalyses"] as? JsonObject)
        ?.mapValues { (_, value) -> value.asStringOrNull152().orEmpty() }
        ?.filterValues { it.isNotBlank() }
        .orEmpty()
    val preferredImageAnalysisMode = element["preferredImageAnalysisMode"]
        ?.asStringOrNull152()
    val aiFileAnalyses = (element["aiFileAnalyses"] as? JsonObject)
        ?.mapValues { (_, value) -> value.asStringOrNull152().orEmpty() }
        ?.filterValues { it.isNotBlank() }
        .orEmpty()
    val preferredFileAnalysisMode = element["preferredFileAnalysisMode"]
        ?.asStringOrNull152()
    val aiFileLastQuestion = element["aiFileLastQuestion"]
        ?.asStringOrNull152()
    val aiAssisted = element["aiAssisted"]
        ?.asStringOrNull152()
        ?.toBooleanStrictOrNull()
        ?: false
    val aiAssistantMode = element["aiAssistantMode"]
        ?.asStringOrNull152()
    val fileName = element["fileName"]?.asStringOrNull152()
    val fileMimeType = element["fileMimeType"]?.asStringOrNull152()
    val fileSizeBytes = element["fileSizeBytes"]?.asStringOrNull152()?.toLongOrNull()
    val attachmentId = element["attachmentId"]?.asStringOrNull152()
    val attachmentKeyBase64 = element["attachmentKeyBase64"]?.asStringOrNull152()
    val attachmentIvBase64 = element["attachmentIvBase64"]?.asStringOrNull152()
    val attachmentCipherSha256 = element["attachmentCipherSha256"]?.asStringOrNull152()
    val attachmentPlainSha256 = element["attachmentPlainSha256"]?.asStringOrNull152()
    val attachmentCipherSize = element["attachmentCipherSize"]?.asStringOrNull152()?.toLongOrNull()
    val markdown = element["markdown"]?.asStringOrNull152()?.toBooleanStrictOrNull() ?: false
    val viewOnce = element["viewOnce"]?.asStringOrNull152()?.toBooleanStrictOrNull() ?: false
    val viewOnceOpened = element["viewOnceOpened"]?.asStringOrNull152()?.toBooleanStrictOrNull() ?: false
    val silent = element["silent"]?.asStringOrNull152()?.toBooleanStrictOrNull() ?: false
    val spoilerMedia = element["spoilerMedia"]?.asStringOrNull152()?.toBooleanStrictOrNull() ?: false
    val spoilerRevealed = element["spoilerRevealed"]?.asStringOrNull152()?.toBooleanStrictOrNull() ?: false
    val forceReply = element["forceReply"]?.asStringOrNull152()?.toBooleanStrictOrNull() ?: false
    val inlineKeyboard = (element["inlineKeyboard"] as? JsonArray)?.mapNotNull { rowEl ->
        val rowArr = rowEl as? JsonArray ?: return@mapNotNull null
        rowArr.mapNotNull { btnEl ->
            val o = btnEl as? JsonObject ?: return@mapNotNull null
            val t = o["text"]?.asStringOrNull152().orEmpty()
            val d = o["callbackData"]?.asStringOrNull152()
                ?: o["callback_data"]?.asStringOrNull152().orEmpty()
            if (t.isBlank()) null
            else InlineKeyboardButton(text = t.take(64), callbackData = d.take(128))
        }.takeIf { it.isNotEmpty() }
    }?.filter { it.isNotEmpty() } ?: emptyList()
    return MessageMeta(
        mentions = mentions,
        replyToId = replyToId,
        forwardedFrom = forwardedFrom,
        voiceTranscript = voiceTranscript,
        voiceDurationMs = voiceDurationMs,
        translations = translations,
        preferredTranslationLanguage = preferredTranslationLanguage,
        aiImageAnalyses = aiImageAnalyses,
        preferredImageAnalysisMode = preferredImageAnalysisMode,
        aiFileAnalyses = aiFileAnalyses,
        preferredFileAnalysisMode = preferredFileAnalysisMode,
        aiFileLastQuestion = aiFileLastQuestion,
        aiAssisted = aiAssisted,
        aiAssistantMode = aiAssistantMode,
        fileName = fileName,
        fileMimeType = fileMimeType,
        fileSizeBytes = fileSizeBytes,
        attachmentId = attachmentId,
        attachmentKeyBase64 = attachmentKeyBase64,
        attachmentIvBase64 = attachmentIvBase64,
        attachmentCipherSha256 = attachmentCipherSha256,
        attachmentPlainSha256 = attachmentPlainSha256,
        attachmentCipherSize = attachmentCipherSize,
        markdown = markdown,
        viewOnce = viewOnce,
        viewOnceOpened = viewOnceOpened,
        silent = silent,
        spoilerMedia = spoilerMedia,
        spoilerRevealed = spoilerRevealed,
        inlineKeyboard = inlineKeyboard,
        forceReply = forceReply
    )
}

internal fun parsedMetaOracle152(content: String, metaField: MessageMeta): MessageMeta {
    val idx = content.lastIndexOf(Message.META_TAG_PREFIX)
    if (idx < 0) return metaField
    val json = content.substring(idx + Message.META_TAG_PREFIX.length).substringBefore("</meta>")
    return runCatching { fromJsonStringOracle152(json) }.getOrDefault(metaField)
}

internal fun messageMetaMapOracle152(meta: MessageMeta): Map<String, Any?> = mapOf(
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
        row.map { btn ->
            mapOf("text" to btn.text, "callbackData" to btn.callbackData)
        }
    },
    "forceReply" to meta.forceReply
)

internal fun toJsonElementOracle152(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is MessageMeta -> toJsonElementOracle152(messageMetaMapOracle152(value))
    is Map<*, *> -> JsonObject(
        value.entries.associate { (k, v) -> k.toString() to toJsonElementOracle152(v) }
    )
    is List<*> -> JsonArray(value.map { toJsonElementOracle152(it) })
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

internal fun encodeMessageMetaOracle152(meta: MessageMeta): String =
    META_JSON152.encodeToString(
        JsonElement.serializer(),
        toJsonElementOracle152(messageMetaMapOracle152(meta)),
    )

internal fun withEncodedMetaOracle152(msg: Message, updatedMeta: MessageMeta): Message {
    val encoded = encodeMessageMetaOracle152(updatedMeta)
    return msg.copy(
        content = parsedContentOracle152(msg.content) + Message.META_TAG_PREFIX + encoded + "</meta>",
        meta = updatedMeta,
    )
}

internal fun isSignalWireEnvelopeOracle152(content: String): Boolean {
    val t = trimStartOracle152(content)
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

internal fun isPlaceholderOracle152(content: String): Boolean {
    val normalized = trimOracle152(content).lowercase()
    if (normalized.isEmpty()) return false
    return normalized in LEGACY_PLACEHOLDERS152
}

internal fun isReadablePlaintextOracle152(content: String): Boolean {
    if (isBlankOracle152(content)) return false
    if (content.startsWith("eyJ")) return false
    if (content.startsWith("MD:") || content.startsWith("SK:")) return false
    if (isSignalWireEnvelopeOracle152(content)) return false
    return !isPlaceholderOracle152(content)
}

internal fun looksLikePersistedWireOracle152(content: String): Boolean {
    if (isBlankOracle152(content)) return false
    if (content.startsWith("eyJ")) return true
    if (content.startsWith("MD:") || content.startsWith("SK:")) return true
    return isSignalWireEnvelopeOracle152(content)
}

internal fun preferReadableOracle152(existing: Message, incoming: Message): Message {
    val existingGood = isReadablePlaintextOracle152(existing.content)
    val incomingGood = isReadablePlaintextOracle152(incoming.content)
    return when {
        existingGood && !incomingGood -> existing
        !existingGood && incomingGood -> incoming
        existingGood && incomingGood -> incoming
        else -> preferWireOracle152(existing, incoming)
    }
}

internal fun preferWireOracle152(existing: Message, incoming: Message): Message {
    val existingWire = looksLikePersistedWireOracle152(existing.content)
    val incomingWire = looksLikePersistedWireOracle152(incoming.content)
    return when {
        existingWire && !incomingWire -> existing
        incomingWire && !existingWire -> incoming
        else -> incoming
    }
}

internal fun mergeDeliveryStatusOracle152(
    existing: MessageStatus,
    incoming: MessageStatus,
): MessageStatus {
    if (existing == incoming) return existing
    if (incoming == MessageStatus.FAILED) {
        return if (existing == MessageStatus.SENDING || existing == MessageStatus.FAILED) {
            MessageStatus.FAILED
        } else {
            existing
        }
    }
    if (existing == MessageStatus.FAILED) {
        return if (incoming == MessageStatus.SENDING || incoming == MessageStatus.SENT) {
            incoming
        } else {
            existing
        }
    }
    return if (existing.deliveryRank() >= incoming.deliveryRank()) existing else incoming
}

internal fun preserveLocalMediaFlagsOracle152(
    existing: Message,
    incoming: Message,
    merged: Message,
): Message {
    val existingMeta = parsedMetaOracle152(existing.content, existing.meta)
    val incomingMeta = parsedMetaOracle152(incoming.content, incoming.meta)
    val mergedMeta = parsedMetaOracle152(merged.content, merged.meta)
    val protectedMeta = mergedMeta.copy(
        viewOnce = mergedMeta.viewOnce || existingMeta.viewOnce || incomingMeta.viewOnce,
        viewOnceOpened = mergedMeta.viewOnceOpened || existingMeta.viewOnceOpened || incomingMeta.viewOnceOpened,
        spoilerMedia = mergedMeta.spoilerMedia || existingMeta.spoilerMedia || incomingMeta.spoilerMedia,
        spoilerRevealed = mergedMeta.spoilerRevealed || existingMeta.spoilerRevealed || incomingMeta.spoilerRevealed
    )
    return if (protectedMeta == mergedMeta) merged else withEncodedMetaOracle152(merged, protectedMeta)
}

internal fun mergeLocalMediaMetaOracle152(current: Message, localSnapshot: Message): Message {
    val currentMeta = parsedMetaOracle152(current.content, current.meta)
    val localMeta = parsedMetaOracle152(localSnapshot.content, localSnapshot.meta)
    val mergedMeta = currentMeta.copy(
        viewOnce = currentMeta.viewOnce || localMeta.viewOnce,
        viewOnceOpened = currentMeta.viewOnceOpened || localMeta.viewOnceOpened,
        spoilerMedia = currentMeta.spoilerMedia || localMeta.spoilerMedia,
        spoilerRevealed = currentMeta.spoilerRevealed || localMeta.spoilerRevealed
    )
    return if (mergedMeta == currentMeta) current else withEncodedMetaOracle152(current, mergedMeta)
}

internal fun mergeMessageOracle152(existing: Message, incoming: Message): Message {
    if (existing.type == MessageType.REVOKED && incoming.type != MessageType.REVOKED) return existing
    if (incoming.type == MessageType.REVOKED) return incoming
    val existingEdit = existing.editedAt ?: Long.MIN_VALUE
    val incomingEdit = incoming.editedAt ?: Long.MIN_VALUE
    val preferred = when {
        incomingEdit > existingEdit -> preferReadableOracle152(existing, incoming)
        incomingEdit < existingEdit -> preferReadableOracle152(incoming, existing)
        else -> preferReadableOracle152(existing, incoming)
    }
    val base = if (incomingEdit >= existingEdit) {
        preferred.copy(
            editedAt = if (incomingEdit > existingEdit) incoming.editedAt else preferred.editedAt,
            starred = incoming.starred,
            reactions = incoming.reactions
        )
    } else {
        preferred.copy(
            starred = preferred.starred,
            reactions = preferred.reactions
        )
    }
    val withStatus = base.copy(
        status = mergeDeliveryStatusOracle152(existing.status, incoming.status),
        sealedSender = existing.sealedSender || incoming.sealedSender,
    )
    return preserveLocalMediaFlagsOracle152(existing, incoming, withStatus)
}

// ---------- generators ----------

internal val CONTENT_TEXT_POOL152 = listOf(
    "hello", "你好世界", "  padded  ", "a & b", "&#65;bc", "&amp;", "&lt;tag&gt;",
    "&#x41;&#x42;", "&#99999999;", "&#0;", "&#x110000;", "no entities here",
    "line1\nline2", "tab\there", "trailing ", "  leading",
)

internal val CONTENT_WIRE_POOL152 = listOf(
    "{\"ciphertext\":\"abc\",\"senderDeviceId\":1}",
    "{\"senderDeviceId\":1,\"payloadType\":\"TEXT\",\"entries\":[{\"ciphertext\":\"x\"}]}",
    "{\"signal-x\":1,\"algorithm\":\"s\"}",
    "[{\"ciphertext\":\"x\"}]",
    "{\"a\":1}",
    "eyJhbGciOiJIUzI1NiJ9",
    "eyJ",
    "MD:deadbeef",
    "SK:deadbeef",
    "MD:",
    "sk:lower",
    "md:lower",
)

internal val CONTENT_PLACEHOLDER_POOL152 = listOf(
    "无法解密", "解密失败", "  [无法解密的消息]  ", "DECRYPT FAILED",
    "Decrypt Failed", "[encrypted message]", "[unable to decrypt video]",
    "无法解密x", "x无法解密", "[无法解密的消息] ",
)

internal fun randomMetaJson152(rnd: Random): String {
    return when (rnd.nextInt(10)) {
        0 -> ""
        1 -> "   "
        2 -> "{}"
        3 -> "{bad json"
        4 -> "[1,2]"
        5 -> "\"str\""
        6 -> "123"
        7 -> {
            // Partial meta with mixed JSON value types (numbers, bools,
            // nulls, arrays) to exercise the per-field safe casts.
            val keys = listOf(
                "\"viewOnce\":true", "\"viewOnce\":\"true\"",
                "\"viewOnce\":1", "\"viewOnce\":null",
                "\"spoilerMedia\":\"false\"", "\"spoilerMedia\":false",
                "\"voiceDurationMs\":12345", "\"voiceDurationMs\":\"678\"",
                "\"voiceDurationMs\":1.5", "\"voiceDurationMs\":true",
                "\"translations\":{\"en\":\"hi\",\"zh\":\"\"}",
                "\"translations\":{\"en\":42}",
                "\"translations\":\"nope\"",
                "\"mentions\":[\"u1\",\"u2\"]", "\"mentions\":[1,2]",
                "\"mentions\":\"u1\"",
                "\"replyToId\":\"m9\"",
                "\"inlineKeyboard\":[[{\"text\":\"ok\",\"callbackData\":\"c1\"}]]",
                "\"inlineKeyboard\":[[{\"text\":\"\",\"callbackData\":\"c1\"}]]",
                "\"inlineKeyboard\":[[{\"text\":\"ok\"}]]",
                "\"inlineKeyboard\":\"nope\"",
                "\"fileSizeBytes\":\"999\"", "\"fileSizeBytes\":9999999999999999999",
                "\"aiAssisted\":\"True\"", "\"aiAssisted\":true",
                "\"markdown\":\"false\"",
                "\"unknownKey\":\"kept?\"",
            )
            val n = rnd.nextInt(1, 4)
            val picked = (0 until n).map { keys[rnd.nextInt(keys.size)] }
            "{${picked.joinToString(",")}}"
        }
        8 -> encodeMessageMetaOracle152(randomMeta152(rnd))
        else -> {
            val meta = randomMeta152(rnd)
            val withFlags = meta.copy(
                viewOnce = rnd.nextBoolean(),
                viewOnceOpened = rnd.nextBoolean(),
                spoilerMedia = rnd.nextBoolean(),
                spoilerRevealed = rnd.nextBoolean(),
            )
            encodeMessageMetaOracle152(withFlags)
        }
    }
}

internal fun randomContent152(rnd: Random): String {
    return when (rnd.nextInt(12)) {
        0, 1 -> CONTENT_TEXT_POOL152[rnd.nextInt(CONTENT_TEXT_POOL152.size)]
        2 -> CONTENT_WIRE_POOL152[rnd.nextInt(CONTENT_WIRE_POOL152.size)]
        3 -> CONTENT_PLACEHOLDER_POOL152[rnd.nextInt(CONTENT_PLACEHOLDER_POOL152.size)]
        4 -> " ".repeat(rnd.nextInt(0, 4))
        5 -> {
            val body = CONTENT_TEXT_POOL152[rnd.nextInt(CONTENT_TEXT_POOL152.size)]
            "$body${Message.META_TAG_PREFIX}${randomMetaJson152(rnd)}</meta>"
        }
        6 -> {
            // Literal <meta> in the body plus a real trailing tag: the
            // parser must take the last one.
            val body = "a<meta>b"
            "$body${Message.META_TAG_PREFIX}${randomMetaJson152(rnd)}</meta>"
        }
        7 -> {
            // Tag without a closing </meta>: substringBefore returns the rest.
            val body = CONTENT_TEXT_POOL152[rnd.nextInt(CONTENT_TEXT_POOL152.size)]
            "$body${Message.META_TAG_PREFIX}${randomMetaJson152(rnd)}"
        }
        8 -> {
            val body = CONTENT_TEXT_POOL152[rnd.nextInt(CONTENT_TEXT_POOL152.size)]
            "$body${Message.META_TAG_PREFIX}"
        }
        9 -> {
            // Two tags: last one wins.
            val body = CONTENT_TEXT_POOL152[rnd.nextInt(CONTENT_TEXT_POOL152.size)]
            "$body${Message.META_TAG_PREFIX}{\"viewOnce\":true}</meta>" +
                "${Message.META_TAG_PREFIX}${randomMetaJson152(rnd)}</meta>"
        }
        10 -> "x".repeat(rnd.nextInt(0, 30))
        else -> {
            val n = rnd.nextInt(1, 6)
            val sb = StringBuilder()
            repeat(n) {
                sb.append("ab12 ".random(rnd))
            }
            sb.toString()
        }
    }
}

internal fun randomMeta152(rnd: Random): MessageMeta {
    val mentions = if (rnd.nextBoolean()) listOf("u1", "u2").take(rnd.nextInt(0, 3)) else emptyList()
    val translations = if (rnd.nextBoolean()) {
        mapOf("en" to "hello", "中文" to "你好").filterKeys { rnd.nextBoolean() }
    } else {
        emptyMap()
    }
    val keyboard = if (rnd.nextInt(10) < 3) {
        listOf(
            listOf(
                InlineKeyboardButton("ok", "c1"),
                InlineKeyboardButton("", "c2"),
            ),
        )
    } else {
        emptyList()
    }
    return MessageMeta(
        mentions = mentions,
        replyToId = if (rnd.nextBoolean()) "m${rnd.nextInt(10)}" else null,
        forwardedFrom = if (rnd.nextBoolean()) "Alice" else null,
        voiceTranscript = if (rnd.nextBoolean()) "transcript" else null,
        voiceDurationMs = if (rnd.nextBoolean()) rnd.nextLong(0, 100000) else null,
        translations = translations,
        preferredTranslationLanguage = if (rnd.nextBoolean()) "en" else null,
        fileName = if (rnd.nextBoolean()) "photo.jpg" else null,
        fileMimeType = if (rnd.nextBoolean()) "image/jpeg" else null,
        fileSizeBytes = if (rnd.nextBoolean()) rnd.nextLong(0, 1000000) else null,
        viewOnce = rnd.nextBoolean(),
        viewOnceOpened = rnd.nextBoolean(),
        silent = rnd.nextBoolean(),
        spoilerMedia = rnd.nextBoolean(),
        spoilerRevealed = rnd.nextBoolean(),
        inlineKeyboard = keyboard,
        forceReply = rnd.nextBoolean(),
    )
}

internal val EDITED_AT_POOL152 = listOf<Long?>(
    null, null, Long.MIN_VALUE, -1L, 0L, 1L, 1000L, Long.MAX_VALUE,
)

internal fun randomMessage152(rnd: Random, index: Int): Message {
    val type = if (rnd.nextInt(10) < 8) {
        MessageType.entries[rnd.nextInt(MessageType.entries.size)]
    } else {
        listOf(MessageType.TEXT, MessageType.REVOKED, MessageType.TEXT)[rnd.nextInt(3)]
    }
    val reactions = List(rnd.nextInt(0, 3)) {
        MessageReaction("u${rnd.nextInt(3)}", listOf("❤️", "👍", "😀")[rnd.nextInt(3)], rnd.nextLong(0, 100000))
    }
    return Message(
        id = "m$index",
        chatId = "c1",
        senderId = "u${rnd.nextInt(3)}",
        content = randomContent152(rnd),
        type = type,
        timestamp = rnd.nextLong(0, 2_000_000_000_000L),
        status = MessageStatus.entries[rnd.nextInt(MessageStatus.entries.size)],
        editedAt = EDITED_AT_POOL152[rnd.nextInt(EDITED_AT_POOL152.size)],
        starred = rnd.nextBoolean(),
        meta = randomMeta152(rnd),
        reactions = reactions,
        expiresAt = if (rnd.nextInt(10) < 2) rnd.nextLong(0, 2_000_000_000_000L) else null,
        sealedSender = rnd.nextBoolean(),
    )
}

internal fun metaJson152(meta: MessageMeta): JSONObject {
    val o = JSONObject()
    val arr = JSONArray()
    for (m in meta.mentions) arr.put(m)
    o.put("mentions", arr)
    if (meta.replyToId != null) o.put("replyToId", meta.replyToId)
    if (meta.forwardedFrom != null) o.put("forwardedFrom", meta.forwardedFrom)
    if (meta.voiceTranscript != null) o.put("voiceTranscript", meta.voiceTranscript)
    if (meta.voiceDurationMs != null) o.put("voiceDurationMs", meta.voiceDurationMs)
    val tr = JSONObject()
    for ((k, v) in meta.translations) tr.put(k, v)
    o.put("translations", tr)
    if (meta.preferredTranslationLanguage != null) o.put("preferredTranslationLanguage", meta.preferredTranslationLanguage)
    val ai = JSONObject()
    for ((k, v) in meta.aiImageAnalyses) ai.put(k, v)
    o.put("aiImageAnalyses", ai)
    if (meta.preferredImageAnalysisMode != null) o.put("preferredImageAnalysisMode", meta.preferredImageAnalysisMode)
    val af = JSONObject()
    for ((k, v) in meta.aiFileAnalyses) af.put(k, v)
    o.put("aiFileAnalyses", af)
    if (meta.preferredFileAnalysisMode != null) o.put("preferredFileAnalysisMode", meta.preferredFileAnalysisMode)
    if (meta.aiFileLastQuestion != null) o.put("aiFileLastQuestion", meta.aiFileLastQuestion)
    o.put("aiAssisted", meta.aiAssisted)
    if (meta.aiAssistantMode != null) o.put("aiAssistantMode", meta.aiAssistantMode)
    if (meta.fileName != null) o.put("fileName", meta.fileName)
    if (meta.fileMimeType != null) o.put("fileMimeType", meta.fileMimeType)
    if (meta.fileSizeBytes != null) o.put("fileSizeBytes", meta.fileSizeBytes)
    if (meta.attachmentId != null) o.put("attachmentId", meta.attachmentId)
    if (meta.attachmentKeyBase64 != null) o.put("attachmentKeyBase64", meta.attachmentKeyBase64)
    if (meta.attachmentIvBase64 != null) o.put("attachmentIvBase64", meta.attachmentIvBase64)
    if (meta.attachmentCipherSha256 != null) o.put("attachmentCipherSha256", meta.attachmentCipherSha256)
    if (meta.attachmentPlainSha256 != null) o.put("attachmentPlainSha256", meta.attachmentPlainSha256)
    if (meta.attachmentCipherSize != null) o.put("attachmentCipherSize", meta.attachmentCipherSize)
    o.put("markdown", meta.markdown)
    o.put("viewOnce", meta.viewOnce)
    o.put("viewOnceOpened", meta.viewOnceOpened)
    o.put("silent", meta.silent)
    o.put("spoilerMedia", meta.spoilerMedia)
    o.put("spoilerRevealed", meta.spoilerRevealed)
    val kb = JSONArray()
    for (row in meta.inlineKeyboard) {
        val r = JSONArray()
        for (btn in row) {
            r.put(JSONObject().put("text", btn.text).put("callbackData", btn.callbackData))
        }
        kb.put(r)
    }
    o.put("inlineKeyboard", kb)
    o.put("forceReply", meta.forceReply)
    return o
}

internal fun messageJson152(m: Message): JSONObject {
    val o = JSONObject()
    o.put("id", m.id)
    o.put("chatId", m.chatId)
    o.put("senderId", m.senderId)
    o.put("content", m.content)
    o.put("type", m.type.name)
    o.put("timestamp", m.timestamp)
    o.put("status", m.status.name)
    if (m.editedAt != null) o.put("editedAt", m.editedAt)
    o.put("starred", m.starred)
    o.put("meta", metaJson152(m.meta))
    val reactions = JSONArray()
    for (r in m.reactions) {
        reactions.put(
            JSONObject().put("userId", r.userId).put("emoji", r.emoji).put("reactedAt", r.reactedAt),
        )
    }
    o.put("reactions", reactions)
    if (m.expiresAt != null) o.put("expiresAt", m.expiresAt)
    o.put("sealedSender", m.sealedSender)
    return o
}

internal fun wirePerMerge152(existing: Message, incoming: Message): String {
    val root = JSONObject()
    root.put("e", messageJson152(existing))
    root.put("i", messageJson152(incoming))
    return root.toString()
}

internal fun wirePerPreserve152(existing: Message, incoming: Message, merged: Message): String {
    val root = JSONObject()
    root.put("e", messageJson152(existing))
    root.put("i", messageJson152(incoming))
    root.put("m", messageJson152(merged))
    return root.toString()
}

internal fun wirePerStatus152(existing: MessageStatus, incoming: MessageStatus): String {
    val root = JSONObject()
    root.put("e", existing.name)
    root.put("i", incoming.name)
    return root.toString()
}

internal fun wirePerParsed152(msg: Message): String =
    JSONObject().put("m", messageJson152(msg)).toString()

class MessagePersistencePolicyFuzzTest {

    private fun msg(
        content: String,
        type: MessageType = MessageType.TEXT,
        status: MessageStatus = MessageStatus.SENT,
        editedAt: Long? = null,
        starred: Boolean = false,
        meta: MessageMeta = MessageMeta(),
        reactions: List<MessageReaction> = emptyList(),
        sealedSender: Boolean = false,
    ) = Message(
        id = "m1", chatId = "c1", senderId = "u1", content = content,
        type = type, timestamp = 1000L, status = status, editedAt = editedAt,
        starred = starred, meta = meta, reactions = reactions, sealedSender = sealedSender,
    )

    @Test
    fun pinnedBoundaries() {
        val plain = msg("hello")
        val wire = msg("{\"ciphertext\":\"x\",\"senderDeviceId\":1}")
        val placeholder = msg("[无法解密的消息]")
        val blank = msg("   ")
        // Readable plaintext beats wire, placeholder and blank.
        assertEquals(plain, mergeMessageOracle152(plain, wire))
        assertEquals(mergeMessageOracle152(plain, wire), mergeMessageForPersistence(plain, wire))
        assertEquals(plain, mergeMessageOracle152(wire, plain))
        assertEquals(mergeMessageOracle152(wire, plain), mergeMessageForPersistence(wire, plain))
        assertEquals(plain, mergeMessageOracle152(placeholder, plain))
        assertEquals(plain, mergeMessageOracle152(blank, plain))
        // Wire beats placeholder; placeholder vs blank falls back to incoming.
        assertEquals(wire, mergeMessageOracle152(wire, placeholder))
        assertEquals(mergeMessageOracle152(wire, placeholder), mergeMessageForPersistence(wire, placeholder))
        assertEquals(placeholder, mergeMessageOracle152(blank, placeholder))
        // REVOKED: existing revoked stays; incoming revoked wins outright.
        val revoked = msg("gone", type = MessageType.REVOKED)
        assertEquals(revoked, mergeMessageOracle152(revoked, plain))
        assertEquals(mergeMessageOracle152(revoked, plain), mergeMessageForPersistence(revoked, plain))
        assertEquals(revoked, mergeMessageOracle152(plain, revoked))
        assertEquals(mergeMessageOracle152(plain, revoked), mergeMessageForPersistence(plain, revoked))
        assertEquals(revoked, mergeMessageOracle152(revoked, revoked))
        // Status ladder: FAILED never clobbers SENT+; FAILED yields to a retry.
        assertEquals(MessageStatus.SENT, mergeDeliveryStatusOracle152(MessageStatus.SENT, MessageStatus.FAILED))
        assertEquals(
            mergeDeliveryStatusOracle152(MessageStatus.SENT, MessageStatus.FAILED),
            mergeDeliveryStatusForPersistence(MessageStatus.SENT, MessageStatus.FAILED),
        )
        assertEquals(MessageStatus.FAILED, mergeDeliveryStatusOracle152(MessageStatus.SENDING, MessageStatus.FAILED))
        assertEquals(MessageStatus.SENDING, mergeDeliveryStatusOracle152(MessageStatus.FAILED, MessageStatus.SENDING))
        assertEquals(MessageStatus.FAILED, mergeDeliveryStatusOracle152(MessageStatus.FAILED, MessageStatus.READ))
        assertEquals(MessageStatus.READ, mergeDeliveryStatusOracle152(MessageStatus.SENT, MessageStatus.READ))
        assertEquals(MessageStatus.READ, mergeDeliveryStatusOracle152(MessageStatus.READ, MessageStatus.SENT))
        // editedAt decides the star/reactions snapshot, not the content pick.
        val oldStarred = msg("old", editedAt = 100, starred = true, reactions = listOf(MessageReaction("u1", "👍", 5)))
        val newPlain = msg("new", editedAt = 200, starred = false)
        val merged = mergeMessageOracle152(oldStarred, newPlain)
        assertEquals("new", merged.content)
        assertEquals(false, merged.starred)
        assertEquals(emptyList(), merged.reactions)
        assertEquals(200L, merged.editedAt)
        assertEquals(mergeMessageOracle152(oldStarred, newPlain), mergeMessageForPersistence(oldStarred, newPlain))
        val mergedBack = mergeMessageOracle152(newPlain, oldStarred)
        assertEquals("new", mergedBack.content)
        assertEquals(false, mergedBack.starred)
        assertEquals(200L, mergedBack.editedAt)
        assertEquals(mergeMessageOracle152(newPlain, oldStarred), mergeMessageForPersistence(newPlain, oldStarred))
        // Sealed sender is sticky across the merge.
        val sealed = mergeMessageOracle152(msg("a", sealedSender = true), msg("b"))
        assertEquals(true, sealed.sealedSender)
        assertEquals(mergeMessageOracle152(msg("a", sealedSender = true), msg("b")), mergeMessageForPersistence(msg("a", sealedSender = true), msg("b")))
        // View-once flag ORs through the <meta> tag even when the merge
        // otherwise keeps the incoming row untouched.
        val tagged = msg("body<meta>{\"viewOnce\":true}</meta>")
        val kept = mergeMessageOracle152(tagged, msg("body2"))
        assertTrue(kept.parsedMeta().viewOnce)
        assertEquals(mergeMessageOracle152(tagged, msg("body2")), mergeMessageForPersistence(tagged, msg("body2")))
        // mergeLocalMediaMetaForPersistence only ORs the four media flags.
        val local = msg("x", meta = MessageMeta(spoilerMedia = true))
        val mergedLocal = mergeLocalMediaMetaOracle152(plain, local)
        assertTrue(mergedLocal.parsedMeta().spoilerMedia)
        assertEquals(mergeLocalMediaMetaOracle152(plain, local), mergeLocalMediaMetaForPersistence(plain, local))
        assertEquals(plain, mergeLocalMediaMetaOracle152(plain, plain))
        // A malformed <meta> block falls back to the Message.meta field.
        val broken = msg("body<meta>{bad</meta>", meta = MessageMeta(viewOnce = true))
        assertTrue(mergeLocalMediaMetaOracle152(broken, plain).parsedMeta().viewOnce)
        assertEquals(
            mergeLocalMediaMetaOracle152(broken, plain),
            mergeLocalMediaMetaForPersistence(broken, plain),
        )
        // HTML entities in the body are unescaped when meta is re-encoded.
        val entity = msg("a &amp; b<meta>{\"viewOnce\":false}</meta>", meta = MessageMeta(viewOnceOpened = true))
        val entityLocal = msg("x", meta = MessageMeta(viewOnceOpened = true))
        val reEncoded = mergeLocalMediaMetaOracle152(entity, entityLocal)
        assertTrue(reEncoded.content.startsWith("a & b<meta>"))
        assertEquals(mergeLocalMediaMetaOracle152(entity, entityLocal), mergeLocalMediaMetaForPersistence(entity, entityLocal))
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(152003)
        repeat(1500) {
            val existing = randomMessage152(rnd, 0)
            val incoming = randomMessage152(rnd, 1)
            assertEquals(
                mergeMessageOracle152(existing, incoming),
                mergeMessageForPersistence(existing, incoming),
            )
            val merged = randomMessage152(rnd, 2)
            assertEquals(
                preserveLocalMediaFlagsOracle152(existing, incoming, merged),
                preserveLocalMediaFlags(existing, incoming, merged),
            )
            assertEquals(
                mergeLocalMediaMetaOracle152(existing, incoming),
                mergeLocalMediaMetaForPersistence(existing, incoming),
            )
            val s1 = MessageStatus.entries[rnd.nextInt(MessageStatus.entries.size)]
            val s2 = MessageStatus.entries[rnd.nextInt(MessageStatus.entries.size)]
            assertEquals(
                mergeDeliveryStatusOracle152(s1, s2),
                mergeDeliveryStatusForPersistence(s1, s2),
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Delivery-status ladder, all 25 pairs.
        for (s1 in MessageStatus.entries) {
            for (s2 in MessageStatus.entries) {
                assertEquals(
                    mergeDeliveryStatusOracle152(s1, s2),
                    mergeDeliveryStatusForPersistence(s1, s2),
                )
            }
        }
        // REVOKED matrix over every message type pair.
        for (t1 in MessageType.entries) {
            for (t2 in MessageType.entries) {
                val a = msg("a", type = t1)
                val b = msg("b", type = t2)
                assertEquals(mergeMessageOracle152(a, b), mergeMessageForPersistence(a, b))
            }
        }
        // editedAt grid: null counts as Long.MIN_VALUE.
        val edits = listOf<Long?>(null, Long.MIN_VALUE, -1L, 0L, 1L, 1000L, Long.MAX_VALUE)
        for (e1 in edits) {
            for (e2 in edits) {
                val a = msg("a", editedAt = e1, starred = true)
                val b = msg("b", editedAt = e2, starred = false)
                assertEquals(mergeMessageOracle152(a, b), mergeMessageForPersistence(a, b))
            }
        }
        // Content-kind preference grid.
        val kinds = listOf(
            "   ", "hello", "eyJhbGciOiJIUzI1NiJ9", "MD:abc", "SK:abc",
            "{\"ciphertext\":\"x\",\"senderDeviceId\":1}",
            "[无法解密的消息]", "body<meta>{\"viewOnce\":true}</meta>",
        )
        for (c1 in kinds) {
            for (c2 in kinds) {
                val a = msg(c1)
                val b = msg(c2)
                assertEquals(mergeMessageOracle152(a, b), mergeMessageForPersistence(a, b))
            }
        }
        // Media-flag OR grid: all 16 flag combos on each side.
        val flagSets = (0 until 16).map { mask ->
            MessageMeta(
                viewOnce = mask and 1 != 0,
                viewOnceOpened = mask and 2 != 0,
                spoilerMedia = mask and 4 != 0,
                spoilerRevealed = mask and 8 != 0,
            )
        }
        for (m1 in flagSets) {
            for (m2 in flagSets) {
                val a = msg("a", meta = m1)
                val b = msg("b", meta = m2)
                assertEquals(preserveLocalMediaFlagsOracle152(a, b, a), preserveLocalMediaFlags(a, b, a))
                assertEquals(mergeLocalMediaMetaOracle152(a, b), mergeLocalMediaMetaForPersistence(a, b))
            }
        }
    }
}
