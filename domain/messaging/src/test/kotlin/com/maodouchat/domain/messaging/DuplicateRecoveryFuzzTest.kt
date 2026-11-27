package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageReaction
import com.maodouchat.core.model.MessagingV2Event
import com.maodouchat.core.model.MessagingV2EventAction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

// ---------- oracle ----------

// A strict JSON value tree, parsed by hand. The grammar mirrors what
// the kotlinx streaming decoder accepts (probed, never assumed):
// whitespace is space/tab/LF/CR only; strings accept raw control
// characters and \u escapes (lone surrogates kept as-is); numbers are
// [-]digits with an optional exponent whose digits may be empty
// ("1e" reads as 1); no '.' in numbers, no '+' sign, no NaN/Infinity.
internal sealed interface JVal181
internal object JNull181 : JVal181
internal data class JBool181(val v: Boolean) : JVal181
internal data class JNum181(val raw: String) : JVal181
internal data class JStr181(val v: String) : JVal181
internal data class JArr181(val items: List<JVal181>) : JVal181
internal data class JObj181(val entries: LinkedHashMap<String, JVal181>) : JVal181

internal class JsonParseFail181 : Exception()

internal class JsonParser181(private val s: String) {
    private var pos = 0

    fun parseTop(): JVal181 {
        val v = parseValue()
        skipWs()
        if (pos != s.length) throw JsonParseFail181()
        return v
    }

    private fun skipWs() {
        while (pos < s.length && s[pos] in " \t\n\r") pos++
    }

    private fun parseValue(): JVal181 {
        skipWs()
        if (pos >= s.length) throw JsonParseFail181()
        return when (val c = s[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JStr181(parseString())
            't' -> { expect("true"); JBool181(true) }
            'f' -> { expect("false"); JBool181(false) }
            'n' -> { expect("null"); JNull181 }
            '-', in '0'..'9' -> parseNumber()
            else -> throw JsonParseFail181()
        }
    }

    private fun expect(word: String) {
        if (!s.startsWith(word, pos)) throw JsonParseFail181()
        pos += word.length
    }

    private fun parseObject(): JVal181 {
        pos++ // {
        val out = LinkedHashMap<String, JVal181>()
        skipWs()
        if (pos < s.length && s[pos] == '}') {
            pos++
            return JObj181(out)
        }
        while (true) {
            skipWs()
            if (pos >= s.length || s[pos] != '"') throw JsonParseFail181()
            val key = parseString()
            skipWs()
            if (pos >= s.length || s[pos] != ':') throw JsonParseFail181()
            pos++
            val value = parseValue()
            out[key] = value
            skipWs()
            if (pos >= s.length) throw JsonParseFail181()
            when (s[pos]) {
                ',' -> { pos++; continue }
                '}' -> { pos++; return JObj181(out) }
                else -> throw JsonParseFail181()
            }
        }
    }

    private fun parseArray(): JVal181 {
        pos++ // [
        val out = ArrayList<JVal181>()
        skipWs()
        if (pos < s.length && s[pos] == ']') {
            pos++
            return JArr181(out)
        }
        while (true) {
            out.add(parseValue())
            skipWs()
            if (pos >= s.length) throw JsonParseFail181()
            when (s[pos]) {
                ',' -> { pos++; continue }
                ']' -> { pos++; return JArr181(out) }
                else -> throw JsonParseFail181()
            }
        }
    }

    private fun parseString(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= s.length) throw JsonParseFail181()
            val c = s[pos]
            when (c) {
                '"' -> { pos++; return sb.toString() }
                '\\' -> {
                    pos++
                    if (pos >= s.length) throw JsonParseFail181()
                    when (val e = s[pos]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\u0008')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= s.length) throw JsonParseFail181()
                            val hex = s.substring(pos + 1, pos + 5)
                            val code = hex.toIntOrNull(16) ?: throw JsonParseFail181()
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw JsonParseFail181()
                    }
                    pos++
                }
                else -> { sb.append(c); pos++ }
            }
        }
    }

    private fun parseNumber(): JVal181 {
        val start = pos
        if (pos < s.length && s[pos] == '-') pos++
        var digits = 0
        while (pos < s.length && s[pos] in '0'..'9') {
            pos++
            digits++
        }
        if (digits == 0) throw JsonParseFail181()
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            pos++
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
        }
        return JNum181(s.substring(start, pos))
    }
}

// The kotlinx number rule, shared by number tokens and string-encoded
// numbers: [-]digits with an optional exponent (empty exponent digits
// mean "no exponent"). Plain integers parse exactly; exponent forms go
// through Double and must come out integral and in range.
internal fun parseKotlinxLong181(raw: String): Long? {
    var i = 0
    if (i < raw.length && raw[i] == '-') i++
    val mantissaStart = i
    while (i < raw.length && raw[i] in '0'..'9') i++
    if (i == mantissaStart) return null
    var expDigits = -1
    if (i < raw.length && (raw[i] == 'e' || raw[i] == 'E')) {
        val afterE = i + 1
        var j = afterE
        if (j < raw.length && (raw[j] == '+' || raw[j] == '-')) j++
        val expStart = j
        while (j < raw.length && raw[j] in '0'..'9') j++
        if (j != raw.length) return null
        expDigits = j - expStart
        if (expDigits == 0) return raw.substring(0, i).toLongOrNull()
    } else if (i != raw.length) {
        return null
    }
    if (expDigits >= 0) {
        val d = raw.toDoubleOrNull() ?: return null
        if (!d.isFinite()) return null
        if (d != kotlin.math.floor(d)) return null
        if (d < Long.MIN_VALUE.toDouble() || d > Long.MAX_VALUE.toDouble()) return null
        // Doubles cannot hold Long.MAX_VALUE exactly; a value that
        // rounds to 2^63 is out of range.
        if (d >= 9223372036854775808.0) return null
        return d.toLong()
    }
    return raw.toLongOrNull()
}

internal fun parseKotlinxInt181(raw: String): Int? {
    val v = parseKotlinxLong181(raw) ?: return null
    if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) return null
    return v.toInt()
}

internal fun parseKotlinxBoolean181(v: JVal181): Boolean? = when (v) {
    is JBool181 -> v.v
    is JStr181 -> when {
        v.v.equals("true", ignoreCase = true) -> true
        v.v.equals("false", ignoreCase = true) -> false
        else -> null
    }
    else -> null
}

internal fun asString181(v: JVal181): String? = (v as? JStr181)?.v

internal fun asLong181(v: JVal181): Long? = when (v) {
    is JNum181 -> parseKotlinxLong181(v.raw)
    is JStr181 -> parseKotlinxLong181(v.v)
    else -> null
}

internal fun asInt181(v: JVal181): Int? = when (v) {
    is JNum181 -> parseKotlinxInt181(v.raw)
    is JStr181 -> parseKotlinxInt181(v.v)
    else -> null
}

internal fun asBool181(v: JVal181): Boolean? = parseKotlinxBoolean181(v)

internal fun asStringList181(v: JVal181): List<String>? {
    if (v !is JArr181) return null
    return v.items.map { asString181(it) ?: return null }
}

internal fun asStringMap181(v: JVal181): Map<String, String>? {
    if (v !is JObj181) return null
    val out = LinkedHashMap<String, String>()
    for ((k, item) in v.entries) {
        out[k] = asString181(item) ?: return null
    }
    return out
}

internal fun decodeMessageMetaOracle181(v: JVal181): MessageMeta? {
    if (v !is JObj181) return null
    val known = setOf(
        "mentions", "replyToId", "forwardedFrom", "voiceTranscript",
        "voiceDurationMs", "translations", "preferredTranslationLanguage",
        "aiImageAnalyses", "preferredImageAnalysisMode", "aiFileAnalyses",
        "preferredFileAnalysisMode", "aiFileLastQuestion", "aiAssisted",
        "markdown", "aiAssistantMode", "fileName", "fileMimeType",
        "fileSizeBytes", "attachmentId", "attachmentKeyBase64",
        "attachmentIvBase64", "attachmentCipherSha256",
        "attachmentPlainSha256", "attachmentCipherSize", "viewOnce",
        "viewOnceOpened", "silent", "spoilerMedia", "spoilerRevealed",
        "inlineKeyboard", "forceReply",
    )
    for (key in v.entries.keys) if (key !in known) return null
    val e = v.entries
    val keyboard = e["inlineKeyboard"]?.let { kb ->
        if (kb !is JArr181) return null
        kb.items.map { row ->
            if (row !is JArr181) return null
            row.items.map { btn ->
                if (btn !is JObj181) return null
                for (bk in btn.entries.keys) {
                    if (bk != "text" && bk != "callbackData") return null
                }
                val text = btn.entries["text"]?.let { asString181(it) ?: return null } ?: ""
                val cb = btn.entries["callbackData"]?.let { asString181(it) ?: return null } ?: ""
                com.maodouchat.core.model.InlineKeyboardButton(text, cb)
            }
        }
    } ?: emptyList()
    return MessageMeta(
        mentions = e["mentions"]?.let { asStringList181(it) ?: return null } ?: emptyList(),
        replyToId = e["replyToId"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        forwardedFrom = e["forwardedFrom"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        voiceTranscript = e["voiceTranscript"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        voiceDurationMs = e["voiceDurationMs"]?.let { if (it is JNull181) null else asLong181(it) ?: return null },
        translations = e["translations"]?.let { asStringMap181(it) ?: return null } ?: emptyMap(),
        preferredTranslationLanguage = e["preferredTranslationLanguage"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        aiImageAnalyses = e["aiImageAnalyses"]?.let { asStringMap181(it) ?: return null } ?: emptyMap(),
        preferredImageAnalysisMode = e["preferredImageAnalysisMode"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        aiFileAnalyses = e["aiFileAnalyses"]?.let { asStringMap181(it) ?: return null } ?: emptyMap(),
        preferredFileAnalysisMode = e["preferredFileAnalysisMode"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        aiFileLastQuestion = e["aiFileLastQuestion"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        aiAssisted = e["aiAssisted"]?.let { asBool181(it) ?: return null } ?: false,
        markdown = e["markdown"]?.let { asBool181(it) ?: return null } ?: false,
        aiAssistantMode = e["aiAssistantMode"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        fileName = e["fileName"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        fileMimeType = e["fileMimeType"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        fileSizeBytes = e["fileSizeBytes"]?.let { if (it is JNull181) null else asLong181(it) ?: return null },
        attachmentId = e["attachmentId"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        attachmentKeyBase64 = e["attachmentKeyBase64"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        attachmentIvBase64 = e["attachmentIvBase64"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        attachmentCipherSha256 = e["attachmentCipherSha256"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        attachmentPlainSha256 = e["attachmentPlainSha256"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        attachmentCipherSize = e["attachmentCipherSize"]?.let { if (it is JNull181) null else asLong181(it) ?: return null },
        viewOnce = e["viewOnce"]?.let { asBool181(it) ?: return null } ?: false,
        viewOnceOpened = e["viewOnceOpened"]?.let { asBool181(it) ?: return null } ?: false,
        silent = e["silent"]?.let { asBool181(it) ?: return null } ?: false,
        spoilerMedia = e["spoilerMedia"]?.let { asBool181(it) ?: return null } ?: false,
        spoilerRevealed = e["spoilerRevealed"]?.let { asBool181(it) ?: return null } ?: false,
        inlineKeyboard = keyboard,
        forceReply = e["forceReply"]?.let { asBool181(it) ?: return null } ?: false,
    )
}

internal fun decodeEventOracle181(v: JVal181): MessagingV2Event? {
    if (v !is JObj181) return null
    val known = setOf(
        "action", "targetMessageId", "content", "editedAt",
        "reactionEmoji", "reactions", "status", "throughMessageId",
    )
    for (key in v.entries.keys) if (key !in known) return null
    val e = v.entries
    val action = e["action"]?.let { asString181(it) } ?: return null
    val target = e["targetMessageId"]?.let { asString181(it) } ?: return null
    val reactions = e["reactions"]?.let { r ->
        if (r !is JArr181) return null
        r.items.map { item ->
            if (item !is JObj181) return null
            for (rk in item.entries.keys) {
                if (rk != "userId" && rk != "emoji" && rk != "reactedAt") return null
            }
            val userId = item.entries["userId"]?.let { asString181(it) } ?: return null
            val emoji = item.entries["emoji"]?.let { asString181(it) } ?: return null
            // reactedAt defaults to now at decode time; the projection
            // drops it, so its value never matters here.
            item.entries["reactedAt"]?.let { asLong181(it) ?: return null }
            MessageReaction(userId, emoji)
        }
    } ?: emptyList()
    return MessagingV2Event(
        action = action,
        targetMessageId = target,
        content = e["content"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        editedAt = e["editedAt"]?.let { if (it is JNull181) null else asLong181(it) ?: return null },
        reactionEmoji = e["reactionEmoji"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        reactions = reactions,
        status = e["status"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        throughMessageId = e["throughMessageId"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
    )
}

internal fun decodeContentOracle181(text: String): MessagingV2Content? {
    val root = try {
        JsonParser181(text).parseTop()
    } catch (_: JsonParseFail181) {
        return null
    }
    if (root !is JObj181) return null
    val known = setOf(
        "version", "type", "body", "replyToMessageId", "attachmentIds",
        "mentionedUserIds", "expiresInSeconds", "attributes", "metadata",
        "event",
    )
    for (key in root.entries.keys) if (key !in known) return null
    val e = root.entries
    val type = e["type"]?.let { asString181(it) } ?: return null
    return MessagingV2Content(
        version = e["version"]?.let { asInt181(it) ?: return null } ?: 1,
        type = type,
        body = e["body"]?.let { asString181(it) ?: return null } ?: "",
        replyToMessageId = e["replyToMessageId"]?.let { if (it is JNull181) null else asString181(it) ?: return null },
        attachmentIds = e["attachmentIds"]?.let { asStringList181(it) ?: return null } ?: emptyList(),
        mentionedUserIds = e["mentionedUserIds"]?.let { asStringList181(it) ?: return null } ?: emptyList(),
        expiresInSeconds = e["expiresInSeconds"]?.let { asInt181(it) ?: return null } ?: 0,
        attributes = e["attributes"]?.let { asStringMap181(it) ?: return null } ?: emptyMap(),
        metadata = e["metadata"]?.let { if (it is JNull181) null else decodeMessageMetaOracle181(it) ?: return null },
        event = e["event"]?.let { if (it is JNull181) null else decodeEventOracle181(it) ?: return null },
    )
}

// The acceptance rules, restated from MessagingV2ContentPolicy and
// MessagingV2ServiceEnvelopePolicy (no production calls here). The
// kind/type/action words are plain literals: the policy keeps its
// constants private, and the event actions are UPPER_SNAKE
// (MessagingV2EventAction), not the lowercase spellings.
internal fun acceptsOracle181(kind: String, c: MessagingV2Content): Boolean {
    val event = c.event
    return when (kind) {
        "DATA" -> {
            c.version in 1..2 && event == null &&
                c.type != "EVENT" &&
                c.type != "SENDER_KEY_REQUEST" &&
                c.type != "SK_DIST"
        }
        "EVENT" -> {
            c.version == 1 && c.type == "EVENT" && event != null &&
                event.action in listOf(
                    MessagingV2EventAction.EDIT, MessagingV2EventAction.REVOKE,
                    MessagingV2EventAction.DELETE, MessagingV2EventAction.REACTION_SET,
                    MessagingV2EventAction.REACTION_SNAPSHOT,
                )
        }
        "RECEIPT" -> {
            c.version == 1 && c.type == "EVENT" && event != null &&
                event.action in listOf(
                    MessagingV2EventAction.DELIVERY_RECEIPT,
                    MessagingV2EventAction.READ_RECEIPT,
                )
        }
        "KEY_REQUEST" -> {
            c.version == 1 && c.type == "SENDER_KEY_REQUEST" &&
                event == null && c.body.isEmpty() && c.attachmentIds.isEmpty() &&
                run {
                    val requested = c.attributes["requestedSenderUserId"]
                    requested != null && requested.isNotBlank() &&
                        requested.length <= 50
                } &&
                run {
                    val failed = c.attributes["failedMessageId"]
                    failed != null && failed.isNotBlank() && failed.length <= 100
                }
        }
        "SERVICE" -> {
            c.version == 1 && acceptsServiceOracle181(c)
        }
        else -> false
    }
}

internal fun acceptsServiceOracle181(c: MessagingV2Content): Boolean {
    val event = c.event
    if (event == null) {
        return c.type != "EVENT" &&
            c.type != "SENDER_KEY_REQUEST" &&
            c.type != "SK_DIST"
    }
    if (c.type != "EVENT") return false
    if (event.targetMessageId.isBlank()) return false
    return when (event.action) {
        MessagingV2EventAction.EDIT -> {
            event.content != null && event.editedAt != null &&
                event.reactionEmoji == null && event.reactions.isEmpty()
        }
        MessagingV2EventAction.DELETE -> {
            event.content == null && event.editedAt == null &&
                event.reactionEmoji == null && event.reactions.isEmpty()
        }
        MessagingV2EventAction.REACTION_SET -> {
            val emoji = event.reactionEmoji
            event.content == null && event.editedAt == null &&
                emoji != null && emoji.isNotBlank() && emoji.length <= 32 &&
                event.reactions.isEmpty()
        }
        else -> false
    }
}

internal fun acceptsServiceEnvelopeOracle181(
    senderUserId: String,
    senderDeviceId: Int,
    ciphertextType: String,
): Boolean =
    senderDeviceId == 0 &&
        ciphertextType == "SERVICE_PLAINTEXT" &&
        (senderUserId.startsWith("bot_") || senderUserId == "system")

internal fun decryptedEnvelopeContentOracle181(kind: String, plaintext: String): MessagingV2Content? {
    val decoded = decodeContentOracle181(plaintext) ?: return null
    return if (acceptsOracle181(kind, decoded)) decoded else null
}

internal fun serviceEnvelopeContentOracle181(
    senderUserId: String,
    senderDeviceId: Int,
    ciphertextType: String,
    kind: String,
    ciphertext: String,
): MessagingV2Content? {
    if (!acceptsServiceEnvelopeOracle181(senderUserId, senderDeviceId, ciphertextType)) return null
    val decoded = decodeContentOracle181(ciphertext) ?: return null
    return if (acceptsOracle181(kind, decoded)) decoded else null
}

internal fun outboxAttachmentIdsOracle181(localPayload: String): List<String> =
    decodeContentOracle181(localPayload)?.attachmentIds ?: emptyList()

// Canonical projection: field order fixed, maps keep first-seen key
// order (last value wins), reactedAt dropped (decode-time default).
internal fun escJsonDup181(s: String): String {
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
                } else if (ch.code in 0xD800..0xDFFF) {
                    // A lone surrogate cannot be re-parsed from a
                    // JSON string, so project it as an escape.
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

internal fun strOrNullJson181(v: String?): String = if (v == null) "null" else escJsonDup181(v)

internal fun longOrNullJson181(v: Long?): String = v?.toString() ?: "null"

internal fun stringListJson181(v: List<String>): String =
    "[" + v.joinToString(",") { escJsonDup181(it) } + "]"

internal fun stringMapJson181(v: Map<String, String>): String =
    "{" + v.entries.joinToString(",") { (k, item) -> escJsonDup181(k) + ":" + escJsonDup181(item) } + "}"

internal fun metadataProjection181(m: MessageMeta?): String {
    if (m == null) return "null"
    return "{" +
        "\"mentions\":" + stringListJson181(m.mentions) +
        ",\"replyToId\":" + strOrNullJson181(m.replyToId) +
        ",\"forwardedFrom\":" + strOrNullJson181(m.forwardedFrom) +
        ",\"voiceTranscript\":" + strOrNullJson181(m.voiceTranscript) +
        ",\"voiceDurationMs\":" + longOrNullJson181(m.voiceDurationMs) +
        ",\"translations\":" + stringMapJson181(m.translations) +
        ",\"preferredTranslationLanguage\":" + strOrNullJson181(m.preferredTranslationLanguage) +
        ",\"aiImageAnalyses\":" + stringMapJson181(m.aiImageAnalyses) +
        ",\"preferredImageAnalysisMode\":" + strOrNullJson181(m.preferredImageAnalysisMode) +
        ",\"aiFileAnalyses\":" + stringMapJson181(m.aiFileAnalyses) +
        ",\"preferredFileAnalysisMode\":" + strOrNullJson181(m.preferredFileAnalysisMode) +
        ",\"aiFileLastQuestion\":" + strOrNullJson181(m.aiFileLastQuestion) +
        ",\"aiAssisted\":" + m.aiAssisted +
        ",\"markdown\":" + m.markdown +
        ",\"aiAssistantMode\":" + strOrNullJson181(m.aiAssistantMode) +
        ",\"fileName\":" + strOrNullJson181(m.fileName) +
        ",\"fileMimeType\":" + strOrNullJson181(m.fileMimeType) +
        ",\"fileSizeBytes\":" + longOrNullJson181(m.fileSizeBytes) +
        ",\"attachmentId\":" + strOrNullJson181(m.attachmentId) +
        ",\"attachmentKeyBase64\":" + strOrNullJson181(m.attachmentKeyBase64) +
        ",\"attachmentIvBase64\":" + strOrNullJson181(m.attachmentIvBase64) +
        ",\"attachmentCipherSha256\":" + strOrNullJson181(m.attachmentCipherSha256) +
        ",\"attachmentPlainSha256\":" + strOrNullJson181(m.attachmentPlainSha256) +
        ",\"attachmentCipherSize\":" + longOrNullJson181(m.attachmentCipherSize) +
        ",\"viewOnce\":" + m.viewOnce +
        ",\"viewOnceOpened\":" + m.viewOnceOpened +
        ",\"silent\":" + m.silent +
        ",\"spoilerMedia\":" + m.spoilerMedia +
        ",\"spoilerRevealed\":" + m.spoilerRevealed +
        ",\"inlineKeyboard\":[" + m.inlineKeyboard.joinToString(",") { row ->
            "[" + row.joinToString(",") { btn ->
                "{\"text\":" + escJsonDup181(btn.text) +
                    ",\"callbackData\":" + escJsonDup181(btn.callbackData) + "}"
            } + "]"
        } + "]" +
        ",\"forceReply\":" + m.forceReply +
        "}"
}

internal fun eventProjection181(e: MessagingV2Event?): String {
    if (e == null) return "null"
    return "{" +
        "\"action\":" + escJsonDup181(e.action) +
        ",\"targetMessageId\":" + escJsonDup181(e.targetMessageId) +
        ",\"content\":" + strOrNullJson181(e.content) +
        ",\"editedAt\":" + longOrNullJson181(e.editedAt) +
        ",\"reactionEmoji\":" + strOrNullJson181(e.reactionEmoji) +
        ",\"reactions\":[" + e.reactions.joinToString(",") { r ->
            "{\"userId\":" + escJsonDup181(r.userId) +
                ",\"emoji\":" + escJsonDup181(r.emoji) + "}"
        } + "]" +
        ",\"status\":" + strOrNullJson181(e.status) +
        ",\"throughMessageId\":" + strOrNullJson181(e.throughMessageId) +
        "}"
}

internal fun contentProjection181(c: MessagingV2Content?): String {
    if (c == null) return "null"
    return "{" +
        "\"version\":" + c.version +
        ",\"type\":" + escJsonDup181(c.type) +
        ",\"body\":" + escJsonDup181(c.body) +
        ",\"replyToMessageId\":" + strOrNullJson181(c.replyToMessageId) +
        ",\"attachmentIds\":" + stringListJson181(c.attachmentIds) +
        ",\"mentionedUserIds\":" + stringListJson181(c.mentionedUserIds) +
        ",\"expiresInSeconds\":" + c.expiresInSeconds +
        ",\"attributes\":" + stringMapJson181(c.attributes) +
        ",\"metadata\":" + metadataProjection181(c.metadata) +
        ",\"event\":" + eventProjection181(c.event) +
        "}"
}


// ---------- duplicate recovery / epoch / receipt ----------

// Character.isWhitespace over all 65,536 UTF-16 units, probed with
// kotlinc 2.4.0 (fuzz179 blank table). U+0085 and U+FEFF are NOT in it.
internal val WS_DUP181: Set<Int> = setOf(
    9, 10, 11, 12, 13, 28, 29, 30, 31, 32, 160, 5760, 8192, 8193, 8194,
    8195, 8196, 8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288
)

internal fun isBlankDup181(s: String): Boolean {
    if (s.isEmpty()) return true
    for (ch in s) if (ch.code !in WS_DUP181) return false
    return true
}

// decideDuplicateRecovery restated: a blank journal counts as no
// journal (then the projection decides, one check); otherwise the
// journal must decode (strict JSON, ignoreUnknownKeys=false) and pass
// the content policy, or it is discarded (no projection check).
internal fun decideOracle181(kind: String, journaledPlaintext: String?, isProjected: Boolean): String {
    val journaled = journaledPlaintext?.takeIf { !isBlankDup181(it) }
        ?: return (if (isProjected) "ACK" else "UNCOMMITTED") + ":1"
    val content = decodeContentOracle181(journaled)
    return if (content != null && acceptsOracle181(kind, content)) {
        "RECOVER:" + contentProjection181(content) + ":0"
    } else {
        "DISCARD:0"
    }
}

// resolveSenderKeyMissingEpoch restated: non-Sender-Key ciphertext
// never repairs (provider not consulted); the envelope revision wins
// (provider not consulted); otherwise the provider's revision, if any,
// must be positive. The wire carries the provider-call count.
internal fun epochOracle181(ciphertextType: String, envelopeRevision: Long?, provided: Long?): String {
    if (ciphertextType != "SENDER_KEY") return "null:0"
    if (envelopeRevision != null) {
        return (if (envelopeRevision > 0L) envelopeRevision.toString() else "null") + ":0"
    }
    val epoch = provided ?: 0L
    return (if (epoch > 0L) epoch.toString() else "null") + ":1"
}

// readReceiptThrough restated: the through id wins, else the target.
internal fun receiptOracle181(throughMessageId: String?, targetMessageId: String): String =
    throughMessageId ?: targetMessageId

// ---------- production ----------

internal fun decideProduction181(kind: String, journaledPlaintext: String?, isProjected: Boolean): String =
    runBlocking {
        var checks = 0
        val r = decideDuplicateRecovery(kind, journaledPlaintext) { checks++; isProjected }
        val outcome = when (r) {
            is DuplicateEnvelopeRecovery.Acknowledge -> "ACK"
            is DuplicateEnvelopeRecovery.Uncommitted -> "UNCOMMITTED"
            is DuplicateEnvelopeRecovery.Recover -> "RECOVER:" + contentProjection181(r.content)
            is DuplicateEnvelopeRecovery.DiscardJournal -> "DISCARD"
        }
        "$outcome:$checks"
    }

internal fun epochProduction181(ciphertextType: String, envelopeRevision: Long?, provided: Long?): String =
    runBlocking {
        var calls = 0
        val r = resolveSenderKeyMissingEpoch(ciphertextType, envelopeRevision) { calls++; provided }
        (r?.toString() ?: "null") + ":$calls"
    }

internal fun receiptProduction181(throughMessageId: String?, targetMessageId: String): String =
    readReceiptThrough(throughMessageId, targetMessageId)

// ---------- generators ----------

internal val V2_KIND_POOL181: List<String> = listOf(
    "DATA", "EVENT", "RECEIPT", "KEY_REQUEST", "SERVICE",
    "", "DATA ", "data", "EVENT ", "SERVICE_PLAINTEXT", "unknown", "key-request",
)

internal val V2_TYPE_POOL181: List<String> = listOf(
    "text", "image", "video", "file", "audio", "voice", "sticker",
    "location", "contact", "event", "sender-key-request", "sk-dist",
    "EVENT", "Event", "sender_key_request", "SENDER_KEY_REQUEST", "SK_DIST",
    "", " ", "text ",
)

internal val V2_BODY_POOL181: List<String> = listOf(
    "", "hello", "你好世界", "a".repeat(100), "line1\nline2",
    "tab\there", "quote\"x", "back\\slash", "emoji \uD83D\uDE00",
    " ", "  ", "\u0001", "null", "true", "0", "  padded  ",
)

internal val V2_NUMBER_POOL181: List<String> = listOf(
    "0", "1", "-1", "2", "01", "007", "-0", "1e2", "1E2", "1e+2",
    "-1e2", "1e", "1e+", "1e-", "1e-2", "15e-1", "1.0", "1.",
    "+1", " 1", "1 ", "2147483647", "2147483648", "-2147483648",
    "9223372036854775807", "9223372036854775808", "-9223372036854775808",
    "9223372036854775807e0", "1e18", "9e18", "1e19", "1e20", "1e100",
    "9007199254740993e0", "0e0", "1e0", "0x10", "NaN", "Infinity",
    "true", "false", "null", "\"1\"", "\"1e2\"", "\"+1\"", "\" 1\"",
    "\"1.0\"", "\"\"", "\"2147483648\"", "\"True\"", "\"TRUE\"",
    "\"false\"", "\"1\"", "\"0\"",
)

internal val V2_BOOL_POOL181: List<String> = listOf(
    "true", "false", "\"true\"", "\"True\"", "\"TRUE\"", "\"false\"",
    "\"False\"", "\"1\"", "\"0\"", "\"\"", "\" true\"", "0", "1", "null",
)

internal val V2_STRING_POOL181: List<String> = listOf(
    "\"\"", "\"x\"", "\"hello\"", "\"a b\"", "\"\\n\"", "\"\\u0041\"",
    "\"\\ud83d\\ude00\"", "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800\\ud800\"",
    "\"tab\\there\"", "\"quote\\\"x\"", "\"back\\\\slash\"", "\"\\u0001\"",
    "\"\\u001f\"", "\"\\u00e9\"", "\"\\u4e2d\\u6587\"", "null", "123",
    "true", "[]", "{}", "\"unterminated", "\"bad\\qescape\"",
    "\"bad\\u12\"", "\"bad\\u12zz\"", "\"raw\nline\"", "\"raw\ttab\"",
)

internal val V2_ACTION_POOL181: List<String> = listOf(
    "EDIT", "REVOKE", "DELETE", "REACTION_SET", "REACTION_SNAPSHOT",
    "DELIVERY_RECEIPT", "READ_RECEIPT", "PLAY_RECEIPT",
    "edit", "revoke", "delete", "reaction_set", "reaction_snapshot",
    "delivery_receipt", "read_receipt", "EDIT ", "edit ", "", "unknown",
    "reaction", "snapshot",
)

internal fun randomJsonText181(rnd: Random, depth: Int): String {
    return when (rnd.nextInt(if (depth <= 0) 4 else 7)) {
        0 -> V2_NUMBER_POOL181[rnd.nextInt(V2_NUMBER_POOL181.size)]
        1 -> V2_STRING_POOL181[rnd.nextInt(V2_STRING_POOL181.size)]
        2 -> if (rnd.nextBoolean()) "true" else "false"
        3 -> "null"
        4 -> {
            val n = rnd.nextInt(0, 4)
            val parts = (0 until n).map {
                val key = when (rnd.nextInt(6)) {
                    0 -> "version"
                    1 -> "type"
                    2 -> "body"
                    3 -> "event"
                    4 -> "unknownKey${rnd.nextInt(5)}"
                    else -> "attr${rnd.nextInt(3)}"
                }
                escJsonDup181(key) + ":" + randomJsonText181(rnd, depth - 1)
            }
            "{" + parts.joinToString(",") + "}"
        }
        5 -> {
            val n = rnd.nextInt(0, 4)
            "[" + (0 until n).joinToString(",") { randomJsonText181(rnd, depth - 1) } + "]"
        }
        else -> "\"" + V2_BODY_POOL181[rnd.nextInt(V2_BODY_POOL181.size)]
            .replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

internal fun randomV2ContentJson181(rnd: Random): String {
    val sb = StringBuilder()
    sb.append("{")
    var first = true
    fun field(name: String, value: String) {
        if (!first) sb.append(",")
        first = false
        sb.append(escJsonDup181(name)).append(":").append(value)
    }
    if (rnd.nextInt(10) < 9) {
        field("version", V2_NUMBER_POOL181[rnd.nextInt(V2_NUMBER_POOL181.size)])
    }
    if (rnd.nextInt(10) < 9) {
        val t = if (rnd.nextInt(5) == 0) {
            V2_STRING_POOL181[rnd.nextInt(V2_STRING_POOL181.size)]
        } else {
            escJsonDup181(V2_TYPE_POOL181[rnd.nextInt(V2_TYPE_POOL181.size)])
        }
        field("type", t)
    }
    if (rnd.nextInt(3) == 0) field("body", escJsonDup181(V2_BODY_POOL181[rnd.nextInt(V2_BODY_POOL181.size)]))
    if (rnd.nextInt(4) == 0) field("replyToMessageId", V2_STRING_POOL181[rnd.nextInt(V2_STRING_POOL181.size)])
    if (rnd.nextInt(4) == 0) {
        val n = rnd.nextInt(0, 3)
        field("attachmentIds", "[" + (0 until n).joinToString(",") { escJsonDup181("att${rnd.nextInt(5)}") } + "]")
    }
    if (rnd.nextInt(4) == 0) {
        field("expiresInSeconds", V2_NUMBER_POOL181[rnd.nextInt(V2_NUMBER_POOL181.size)])
    }
    if (rnd.nextInt(4) == 0) {
        val pairs = mutableListOf<String>()
        if (rnd.nextBoolean()) pairs.add(escJsonDup181("requestedSenderUserId") + ":" + escJsonDup181(if (rnd.nextBoolean()) "user${rnd.nextInt(3)}" else " ".repeat(rnd.nextInt(3))))
        if (rnd.nextBoolean()) pairs.add(escJsonDup181("failedMessageId") + ":" + escJsonDup181("msg${rnd.nextInt(3)}"))
        if (rnd.nextBoolean()) pairs.add(escJsonDup181("k${rnd.nextInt(3)}") + ":" + escJsonDup181("v${rnd.nextInt(3)}"))
        field("attributes", "{" + pairs.joinToString(",") + "}")
    }
    if (rnd.nextInt(3) == 0) {
        val meta = StringBuilder()
        meta.append("{")
        var mFirst = true
        fun mField(name: String, value: String) {
            if (!mFirst) meta.append(",")
            mFirst = false
            meta.append(escJsonDup181(name)).append(":").append(value)
        }
        if (rnd.nextBoolean()) mField("aiAssisted", V2_BOOL_POOL181[rnd.nextInt(V2_BOOL_POOL181.size)])
        if (rnd.nextBoolean()) mField("voiceDurationMs", V2_NUMBER_POOL181[rnd.nextInt(V2_NUMBER_POOL181.size)])
        if (rnd.nextBoolean()) mField("translations", "{\"en\":" + escJsonDup181("hello") + ",\"zh\":" + escJsonDup181("你好") + "}")
        if (rnd.nextBoolean()) mField("inlineKeyboard", "[[{\"text\":" + escJsonDup181("ok") + ",\"callbackData\":" + escJsonDup181("cb") + "}]]")
        if (rnd.nextBoolean()) mField("unknownMeta", "\"x\"")
        meta.append("}")
        field("metadata", meta.toString())
    }
    if (rnd.nextInt(2) == 0) {
        val ev = StringBuilder()
        ev.append("{")
        var eFirst = true
        fun eField(name: String, value: String) {
            if (!eFirst) ev.append(",")
            eFirst = false
            ev.append(escJsonDup181(name)).append(":").append(value)
        }
        if (rnd.nextInt(10) < 9) eField("action", escJsonDup181(V2_ACTION_POOL181[rnd.nextInt(V2_ACTION_POOL181.size)]))
        if (rnd.nextInt(10) < 9) eField("targetMessageId", escJsonDup181("msg${rnd.nextInt(5)}"))
        if (rnd.nextBoolean()) eField("content", escJsonDup181(V2_BODY_POOL181[rnd.nextInt(V2_BODY_POOL181.size)]))
        if (rnd.nextBoolean()) eField("editedAt", V2_NUMBER_POOL181[rnd.nextInt(V2_NUMBER_POOL181.size)])
        if (rnd.nextBoolean()) eField("reactionEmoji", escJsonDup181(if (rnd.nextBoolean()) "\uD83D\uDE00" else ""))
        if (rnd.nextBoolean()) {
            eField("reactions", "[{\"userId\":" + escJsonDup181("u1") + ",\"emoji\":" + escJsonDup181("\u2764") + ",\"reactedAt\":" + V2_NUMBER_POOL181[rnd.nextInt(V2_NUMBER_POOL181.size)] + "}]")
        }
        ev.append("}")
        field("event", ev.toString())
    }
    if (rnd.nextInt(6) == 0) field("unknownTop", "\"x\"")
    sb.append("}")
    var text = sb.toString()
    when (rnd.nextInt(8)) {
        1 -> text = " " + text + " "
        2 -> text = text.replaceFirst("{", "{\"type\":${escJsonDup181("text")},")
        3 -> text = text.dropLast(1)
        4 -> text = text + "x"
        5 -> text = text.replace("\"type\"", "\"type\"", false)
        6 -> text = text.replaceFirst("}", ",}")
        7 -> text = "not json at all"
    }
    return text
}

internal data class V2Case181(
    val fn: String,
    val kind: String,
    val senderUserId: String,
    val senderDeviceId: Int,
    val ciphertextType: String,
    val text: String,
)

internal fun randomV2Case181(rnd: Random): V2Case181 {
    val fn = when (rnd.nextInt(3)) {
        0 -> "decrypted"
        1 -> "service"
        else -> "outbox"
    }
    val text = when (rnd.nextInt(4)) {
        0 -> randomV2ContentJson181(rnd)
        1 -> randomJsonText181(rnd, 3)
        2 -> V2_BODY_POOL181[rnd.nextInt(V2_BODY_POOL181.size)]
        else -> ""
    }
    return V2Case181(
        fn = fn,
        kind = V2_KIND_POOL181[rnd.nextInt(V2_KIND_POOL181.size)],
        senderUserId = when (rnd.nextInt(4)) {
            0 -> "bot_123"
            1 -> "system"
            2 -> "user${rnd.nextInt(5)}"
            else -> ""
        },
        senderDeviceId = listOf(0, 1, -1, 2, Int.MAX_VALUE, Int.MIN_VALUE)[rnd.nextInt(6)],
        ciphertextType = when (rnd.nextInt(4)) {
            0 -> "SERVICE_PLAINTEXT"
            1 -> "SERVICE_CIPHERTEXT"
            2 -> ""
            else -> "service_plaintext"
        },
        text = text,
    )
}

internal fun v2CaseWireJson181(c: V2Case181): String =
    "{\"fn\":" + escJsonDup181(c.fn) +
        ",\"kind\":" + escJsonDup181(c.kind) +
        ",\"senderUserId\":" + escJsonDup181(c.senderUserId) +
        ",\"senderDeviceId\":" + c.senderDeviceId +
        ",\"ciphertextType\":" + escJsonDup181(c.ciphertextType) +
        ",\"text\":" + escJsonDup181(c.text) + "}"


internal data class DupCase181(
    val kind: String,
    val journal: String?,
    val projected: Boolean,
)

internal data class EpochCase181(
    val ciphertextType: String,
    val envelope: Long?,
    val provided: Long?,
)

internal data class ReceiptCase181(
    val through: String?,
    val target: String,
)

internal val DUP_TYPE_POOL181: List<String> = listOf(
    "SENDER_KEY", "TEXT", "", "sender_key", "SENDER_KEY ", "DEVICE", "GROUP", "sender-key",
)

internal val DUP_LONG_POOL181: List<Long?> = listOf(
    null, 0L, 1L, -1L, 2L, 9L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1,
)

internal fun randomDupCase181(rnd: Random): DupCase181 {
    val kind = V2_KIND_POOL181[rnd.nextInt(V2_KIND_POOL181.size)]
    val journal = when (rnd.nextInt(6)) {
        0 -> null
        1 -> ""
        2 -> " ".repeat(rnd.nextInt(0, 5))
        3 -> randomV2ContentJson181(rnd)
        4 -> randomJsonText181(rnd, 3)
        else -> PRIV_DUP_JOURNALS181[rnd.nextInt(PRIV_DUP_JOURNALS181.size)]
    }
    return DupCase181(kind, journal, rnd.nextBoolean())
}

internal fun randomEpochCase181(rnd: Random): EpochCase181 {
    val type = DUP_TYPE_POOL181[rnd.nextInt(DUP_TYPE_POOL181.size)]
    val envelope = DUP_LONG_POOL181[rnd.nextInt(DUP_LONG_POOL181.size)]
    val provided = DUP_LONG_POOL181[rnd.nextInt(DUP_LONG_POOL181.size)]
    return EpochCase181(type, envelope, provided)
}

internal fun randomReceiptCase181(rnd: Random): ReceiptCase181 {
    val through = when (rnd.nextInt(4)) {
        0 -> null
        1 -> ""
        2 -> "m" + rnd.nextInt(100)
        else -> "through-" + rnd.nextInt(100)
    }
    return ReceiptCase181(through, "target-" + rnd.nextInt(100))
}

internal fun strOrNullJson181b(v: String?): String = if (v == null) "null" else escJsonDup181(v)

internal fun dupCaseWireJson181(c: DupCase181): String =
    "{\"kind\":" + escJsonDup181(c.kind) +
        ",\"journal\":" + strOrNullJson181b(c.journal) +
        ",\"projected\":" + c.projected + "}"

internal fun epochCaseWireJson181(c: EpochCase181): String =
    "{\"ciphertextType\":" + escJsonDup181(c.ciphertextType) +
        ",\"envelope\":" + longOrNullJson181(c.envelope) +
        ",\"provided\":" + longOrNullJson181(c.provided) + "}"

internal fun receiptCaseWireJson181(c: ReceiptCase181): String =
    "{\"through\":" + strOrNullJson181b(c.through) +
        ",\"target\":" + escJsonDup181(c.target) + "}"

internal val PRIV_DUP_JOURNALS181: List<String> = listOf(
    "{\"version\":1,\"type\":\"TEXT\",\"body\":\"hello\"}",
    "{\"version\":1,\"type\":\"EVENT\",\"event\":{\"action\":\"DELETE\",\"targetMessageId\":\"m1\"}}",
    "{\"version\":2,\"type\":\"text\"}",
    "{\"version\":1,\"type\":\"SENDER_KEY_REQUEST\",\"attributes\":{\"requestedSenderUserId\":\"u1\",\"failedMessageId\":\"m1\"}}",
    "{\"version\":1,\"type\":\"text\",\"unknown\":1}",
    "{\"type\":\"text\"}",
    "not-json{{{",
    "{\"version\":1,\"type\":\"text\",\"body\":123}",
    "{\"version\":1,\"type\":\"EVENT\",\"event\":{\"action\":\"EDIT\",\"targetMessageId\":\"m1\",\"content\":\"x\",\"editedAt\":5}}",
    "{\"version\":1,\"type\":\"EVENT\",\"event\":{\"action\":\"REACTION_SET\",\"targetMessageId\":\"m1\",\"reactionEmoji\":\"\u2764\"}}",
    "{\"version\":1,\"type\":\"text\",\"event\":{\"action\":\"EDIT\",\"targetMessageId\":\"m1\"}}",
    "{}",
    "null",
    "[]",
)

class DuplicateRecoveryFuzzTest {

    private fun checkDup181(c: DupCase181) {
        assertEquals(
            decideOracle181(c.kind, c.journal, c.projected),
            decideProduction181(c.kind, c.journal, c.projected),
            "decide($c)"
        )
    }

    private fun checkEpoch181(c: EpochCase181) {
        assertEquals(
            epochOracle181(c.ciphertextType, c.envelope, c.provided),
            epochProduction181(c.ciphertextType, c.envelope, c.provided),
            "epoch($c)"
        )
    }

    private fun checkReceipt181(c: ReceiptCase181) {
        assertEquals(
            receiptOracle181(c.through, c.target),
            receiptProduction181(c.through, c.target),
            "receipt($c)"
        )
    }

    @Test
    fun pinnedRecovery() {
        // The pinned cases from DuplicateEnvelopeRecoveryTest /
        // SenderKeyMissingEpochTest / ReadReceiptThroughTest, plus
        // journal/policy edges.
        val dataPayload = "{\"version\":1,\"type\":\"TEXT\",\"body\":\"hello\"}"
        checkDup181(DupCase181("DATA", null, true))
        checkDup181(DupCase181("DATA", null, false))
        checkDup181(DupCase181("DATA", "   ", false))
        checkDup181(DupCase181("DATA", " \t\n ", true))
        checkDup181(DupCase181("DATA", dataPayload, true))
        checkDup181(DupCase181("DATA", dataPayload, false))
        checkDup181(DupCase181("RECEIPT", dataPayload, true))
        checkDup181(DupCase181("EVENT", dataPayload, false))
        checkDup181(DupCase181("DATA", "not-json{{{", true))
        checkDup181(DupCase181("DATA", "", true))
        val eventPayload = "{\"version\":1,\"type\":\"EVENT\",\"event\":{\"action\":\"DELETE\",\"targetMessageId\":\"m1\"}}"
        checkDup181(DupCase181("EVENT", eventPayload, false))
        checkDup181(DupCase181("SERVICE", eventPayload, true))
        for (journal in PRIV_DUP_JOURNALS181) {
            for (kind in V2_KIND_POOL181) {
                checkDup181(DupCase181(kind, journal, true))
                checkDup181(DupCase181(kind, journal, false))
            }
        }
        for (c in listOf(
            EpochCase181("TEXT", null, 9L),
            EpochCase181("SENDER_KEY", 9L, 7L),
            EpochCase181("SENDER_KEY", null, 7L),
            EpochCase181("SENDER_KEY", null, null),
            EpochCase181("SENDER_KEY", null, 0L),
            EpochCase181("SENDER_KEY", 0L, 5L),
            EpochCase181("SENDER_KEY", -3L, 5L),
            EpochCase181("SENDER_KEY", Long.MAX_VALUE, null),
            EpochCase181("SENDER_KEY", null, Long.MIN_VALUE),
            EpochCase181("", null, 5L),
        )) checkEpoch181(c)
        for (c in listOf(
            ReceiptCase181(null, "t1"),
            ReceiptCase181("", "t1"),
            ReceiptCase181("through-9", "t1"),
            ReceiptCase181(" ", "t1"),
        )) checkReceipt181(c)
    }

    @Test
    fun randomRecoveryMatchesProduction() {
        val rnd = Random(181003)
        repeat(2000) { checkDup181(randomDupCase181(rnd)) }
        repeat(1000) { checkEpoch181(randomEpochCase181(rnd)) }
        repeat(1000) { checkReceipt181(randomReceiptCase181(rnd)) }
    }

    @Test
    fun sweepRecoveryBoundaries() {
        // Every kind over the same journal: only the policy differs.
        val journal = "{\"version\":1,\"type\":\"TEXT\",\"body\":\"hello\"}"
        for (kind in V2_KIND_POOL181) {
            checkDup181(DupCase181(kind, journal, true))
            checkDup181(DupCase181(kind, journal, false))
        }
        // Blank-journal flavours: every whitespace unit counts as blank.
        for (ws in listOf(" ", "\t", "\n", "\r", "\u000B", "\u000C", "\u00A0", "\u2003", "\u0085", "\uFEFF", "\u2028", "\u2029")) {
            checkDup181(DupCase181("DATA", ws, false))
            checkDup181(DupCase181("DATA", ws + ws, true))
        }
        // Epoch boundaries: 0 / -1 / 1 / extremes, envelope vs provider.
        for (env in DUP_LONG_POOL181) {
            for (prov in DUP_LONG_POOL181) {
                checkEpoch181(EpochCase181("SENDER_KEY", env, prov))
            }
        }
        // Ciphertext-type spellings around SENDER_KEY.
        for (type in listOf("SENDER_KEY", "sender_key", "SENDER_KEY ", " SENDER_KEY", "SENDER-KEY", "SENDER_KEYX", "")) {
            checkEpoch181(EpochCase181(type, null, 5L))
            checkEpoch181(EpochCase181(type, 5L, null))
        }
        // Receipt: through wins even when empty or blank.
        for (through in listOf(null, "", " ", "x", "through-1")) {
            checkReceipt181(ReceiptCase181(through, "target-1"))
        }
    }
}
