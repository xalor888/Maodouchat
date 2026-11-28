package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageReaction
import com.maodouchat.core.model.MessagingV2Event
import com.maodouchat.core.model.MessagingV2EventAction
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// A strict JSON value tree, parsed by hand. The grammar mirrors what
// the kotlinx streaming decoder accepts (probed, never assumed):
// whitespace is space/tab/LF/CR only; strings accept raw control
// characters and \u escapes (lone surrogates kept as-is); numbers are
// [-]digits with an optional exponent whose digits may be empty
// ("1e" reads as 1); no '.' in numbers, no '+' sign, no NaN/Infinity.
internal sealed interface JVal180
internal object JNull180 : JVal180
internal data class JBool180(val v: Boolean) : JVal180
internal data class JNum180(val raw: String) : JVal180
internal data class JStr180(val v: String) : JVal180
internal data class JArr180(val items: List<JVal180>) : JVal180
internal data class JObj180(val entries: LinkedHashMap<String, JVal180>) : JVal180

internal class JsonParseFail180 : Exception()

internal class JsonParser180(private val s: String) {
    private var pos = 0

    fun parseTop(): JVal180 {
        val v = parseValue()
        skipWs()
        if (pos != s.length) throw JsonParseFail180()
        return v
    }

    private fun skipWs() {
        while (pos < s.length && s[pos] in " \t\n\r") pos++
    }

    private fun parseValue(): JVal180 {
        skipWs()
        if (pos >= s.length) throw JsonParseFail180()
        return when (val c = s[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JStr180(parseString())
            't' -> { expect("true"); JBool180(true) }
            'f' -> { expect("false"); JBool180(false) }
            'n' -> { expect("null"); JNull180 }
            '-', in '0'..'9' -> parseNumber()
            else -> throw JsonParseFail180()
        }
    }

    private fun expect(word: String) {
        if (!s.startsWith(word, pos)) throw JsonParseFail180()
        pos += word.length
    }

    private fun parseObject(): JVal180 {
        pos++ // {
        val out = LinkedHashMap<String, JVal180>()
        skipWs()
        if (pos < s.length && s[pos] == '}') {
            pos++
            return JObj180(out)
        }
        while (true) {
            skipWs()
            if (pos >= s.length || s[pos] != '"') throw JsonParseFail180()
            val key = parseString()
            skipWs()
            if (pos >= s.length || s[pos] != ':') throw JsonParseFail180()
            pos++
            val value = parseValue()
            out[key] = value
            skipWs()
            if (pos >= s.length) throw JsonParseFail180()
            when (s[pos]) {
                ',' -> { pos++; continue }
                '}' -> { pos++; return JObj180(out) }
                else -> throw JsonParseFail180()
            }
        }
    }

    private fun parseArray(): JVal180 {
        pos++ // [
        val out = ArrayList<JVal180>()
        skipWs()
        if (pos < s.length && s[pos] == ']') {
            pos++
            return JArr180(out)
        }
        while (true) {
            out.add(parseValue())
            skipWs()
            if (pos >= s.length) throw JsonParseFail180()
            when (s[pos]) {
                ',' -> { pos++; continue }
                ']' -> { pos++; return JArr180(out) }
                else -> throw JsonParseFail180()
            }
        }
    }

    private fun parseString(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= s.length) throw JsonParseFail180()
            val c = s[pos]
            when (c) {
                '"' -> { pos++; return sb.toString() }
                '\\' -> {
                    pos++
                    if (pos >= s.length) throw JsonParseFail180()
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
                            if (pos + 4 >= s.length) throw JsonParseFail180()
                            val hex = s.substring(pos + 1, pos + 5)
                            val code = hex.toIntOrNull(16) ?: throw JsonParseFail180()
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw JsonParseFail180()
                    }
                    pos++
                }
                else -> { sb.append(c); pos++ }
            }
        }
    }

    private fun parseNumber(): JVal180 {
        val start = pos
        if (pos < s.length && s[pos] == '-') pos++
        var digits = 0
        while (pos < s.length && s[pos] in '0'..'9') {
            pos++
            digits++
        }
        if (digits == 0) throw JsonParseFail180()
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            pos++
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
        }
        return JNum180(s.substring(start, pos))
    }
}

// The kotlinx number rule, shared by number tokens and string-encoded
// numbers: [-]digits with an optional exponent (empty exponent digits
// mean "no exponent"). Plain integers parse exactly; exponent forms go
// through Double and must come out integral and in range.
internal fun parseKotlinxLong180(raw: String): Long? {
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

internal fun parseKotlinxInt180(raw: String): Int? {
    val v = parseKotlinxLong180(raw) ?: return null
    if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) return null
    return v.toInt()
}

internal fun parseKotlinxBoolean180(v: JVal180): Boolean? = when (v) {
    is JBool180 -> v.v
    is JStr180 -> when {
        v.v.equals("true", ignoreCase = true) -> true
        v.v.equals("false", ignoreCase = true) -> false
        else -> null
    }
    else -> null
}

internal fun asString180(v: JVal180): String? = (v as? JStr180)?.v

internal fun asLong180(v: JVal180): Long? = when (v) {
    is JNum180 -> parseKotlinxLong180(v.raw)
    is JStr180 -> parseKotlinxLong180(v.v)
    else -> null
}

internal fun asInt180(v: JVal180): Int? = when (v) {
    is JNum180 -> parseKotlinxInt180(v.raw)
    is JStr180 -> parseKotlinxInt180(v.v)
    else -> null
}

internal fun asBool180(v: JVal180): Boolean? = parseKotlinxBoolean180(v)

internal fun asStringList180(v: JVal180): List<String>? {
    if (v !is JArr180) return null
    return v.items.map { asString180(it) ?: return null }
}

internal fun asStringMap180(v: JVal180): Map<String, String>? {
    if (v !is JObj180) return null
    val out = LinkedHashMap<String, String>()
    for ((k, item) in v.entries) {
        out[k] = asString180(item) ?: return null
    }
    return out
}

internal fun decodeMessageMetaOracle180(v: JVal180): MessageMeta? {
    if (v !is JObj180) return null
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
        if (kb !is JArr180) return null
        kb.items.map { row ->
            if (row !is JArr180) return null
            row.items.map { btn ->
                if (btn !is JObj180) return null
                for (bk in btn.entries.keys) {
                    if (bk != "text" && bk != "callbackData") return null
                }
                val text = btn.entries["text"]?.let { asString180(it) ?: return null } ?: ""
                val cb = btn.entries["callbackData"]?.let { asString180(it) ?: return null } ?: ""
                com.maodouchat.core.model.InlineKeyboardButton(text, cb)
            }
        }
    } ?: emptyList()
    return MessageMeta(
        mentions = e["mentions"]?.let { asStringList180(it) ?: return null } ?: emptyList(),
        replyToId = e["replyToId"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        forwardedFrom = e["forwardedFrom"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        voiceTranscript = e["voiceTranscript"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        voiceDurationMs = e["voiceDurationMs"]?.let { if (it is JNull180) null else asLong180(it) ?: return null },
        translations = e["translations"]?.let { asStringMap180(it) ?: return null } ?: emptyMap(),
        preferredTranslationLanguage = e["preferredTranslationLanguage"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        aiImageAnalyses = e["aiImageAnalyses"]?.let { asStringMap180(it) ?: return null } ?: emptyMap(),
        preferredImageAnalysisMode = e["preferredImageAnalysisMode"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        aiFileAnalyses = e["aiFileAnalyses"]?.let { asStringMap180(it) ?: return null } ?: emptyMap(),
        preferredFileAnalysisMode = e["preferredFileAnalysisMode"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        aiFileLastQuestion = e["aiFileLastQuestion"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        aiAssisted = e["aiAssisted"]?.let { asBool180(it) ?: return null } ?: false,
        markdown = e["markdown"]?.let { asBool180(it) ?: return null } ?: false,
        aiAssistantMode = e["aiAssistantMode"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        fileName = e["fileName"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        fileMimeType = e["fileMimeType"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        fileSizeBytes = e["fileSizeBytes"]?.let { if (it is JNull180) null else asLong180(it) ?: return null },
        attachmentId = e["attachmentId"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        attachmentKeyBase64 = e["attachmentKeyBase64"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        attachmentIvBase64 = e["attachmentIvBase64"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        attachmentCipherSha256 = e["attachmentCipherSha256"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        attachmentPlainSha256 = e["attachmentPlainSha256"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        attachmentCipherSize = e["attachmentCipherSize"]?.let { if (it is JNull180) null else asLong180(it) ?: return null },
        viewOnce = e["viewOnce"]?.let { asBool180(it) ?: return null } ?: false,
        viewOnceOpened = e["viewOnceOpened"]?.let { asBool180(it) ?: return null } ?: false,
        silent = e["silent"]?.let { asBool180(it) ?: return null } ?: false,
        spoilerMedia = e["spoilerMedia"]?.let { asBool180(it) ?: return null } ?: false,
        spoilerRevealed = e["spoilerRevealed"]?.let { asBool180(it) ?: return null } ?: false,
        inlineKeyboard = keyboard,
        forceReply = e["forceReply"]?.let { asBool180(it) ?: return null } ?: false,
    )
}

internal fun decodeEventOracle180(v: JVal180): MessagingV2Event? {
    if (v !is JObj180) return null
    val known = setOf(
        "action", "targetMessageId", "content", "editedAt",
        "reactionEmoji", "reactions", "status", "throughMessageId",
    )
    for (key in v.entries.keys) if (key !in known) return null
    val e = v.entries
    val action = e["action"]?.let { asString180(it) } ?: return null
    val target = e["targetMessageId"]?.let { asString180(it) } ?: return null
    val reactions = e["reactions"]?.let { r ->
        if (r !is JArr180) return null
        r.items.map { item ->
            if (item !is JObj180) return null
            for (rk in item.entries.keys) {
                if (rk != "userId" && rk != "emoji" && rk != "reactedAt") return null
            }
            val userId = item.entries["userId"]?.let { asString180(it) } ?: return null
            val emoji = item.entries["emoji"]?.let { asString180(it) } ?: return null
            // reactedAt defaults to now at decode time; the projection
            // drops it, so its value never matters here.
            item.entries["reactedAt"]?.let { asLong180(it) ?: return null }
            MessageReaction(userId, emoji)
        }
    } ?: emptyList()
    return MessagingV2Event(
        action = action,
        targetMessageId = target,
        content = e["content"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        editedAt = e["editedAt"]?.let { if (it is JNull180) null else asLong180(it) ?: return null },
        reactionEmoji = e["reactionEmoji"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        reactions = reactions,
        status = e["status"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        throughMessageId = e["throughMessageId"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
    )
}

internal fun decodeContentOracle180(text: String): MessagingV2Content? {
    val root = try {
        JsonParser180(text).parseTop()
    } catch (_: JsonParseFail180) {
        return null
    }
    if (root !is JObj180) return null
    val known = setOf(
        "version", "type", "body", "replyToMessageId", "attachmentIds",
        "mentionedUserIds", "expiresInSeconds", "attributes", "metadata",
        "event",
    )
    for (key in root.entries.keys) if (key !in known) return null
    val e = root.entries
    val type = e["type"]?.let { asString180(it) } ?: return null
    return MessagingV2Content(
        version = e["version"]?.let { asInt180(it) ?: return null } ?: 1,
        type = type,
        body = e["body"]?.let { asString180(it) ?: return null } ?: "",
        replyToMessageId = e["replyToMessageId"]?.let { if (it is JNull180) null else asString180(it) ?: return null },
        attachmentIds = e["attachmentIds"]?.let { asStringList180(it) ?: return null } ?: emptyList(),
        mentionedUserIds = e["mentionedUserIds"]?.let { asStringList180(it) ?: return null } ?: emptyList(),
        expiresInSeconds = e["expiresInSeconds"]?.let { asInt180(it) ?: return null } ?: 0,
        attributes = e["attributes"]?.let { asStringMap180(it) ?: return null } ?: emptyMap(),
        metadata = e["metadata"]?.let { if (it is JNull180) null else decodeMessageMetaOracle180(it) ?: return null },
        event = e["event"]?.let { if (it is JNull180) null else decodeEventOracle180(it) ?: return null },
    )
}

// The acceptance rules, restated from MessagingV2ContentPolicy and
// MessagingV2ServiceEnvelopePolicy (no production calls here). The
// kind/type/action words are plain literals: the policy keeps its
// constants private, and the event actions are UPPER_SNAKE
// (MessagingV2EventAction), not the lowercase spellings.
internal fun acceptsOracle180(kind: String, c: MessagingV2Content): Boolean {
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
            c.version == 1 && acceptsServiceOracle180(c)
        }
        else -> false
    }
}

internal fun acceptsServiceOracle180(c: MessagingV2Content): Boolean {
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

internal fun acceptsServiceEnvelopeOracle180(
    senderUserId: String,
    senderDeviceId: Int,
    ciphertextType: String,
): Boolean =
    senderDeviceId == 0 &&
        ciphertextType == "SERVICE_PLAINTEXT" &&
        (senderUserId.startsWith("bot_") || senderUserId == "system")

internal fun decryptedEnvelopeContentOracle180(kind: String, plaintext: String): MessagingV2Content? {
    val decoded = decodeContentOracle180(plaintext) ?: return null
    return if (acceptsOracle180(kind, decoded)) decoded else null
}

internal fun serviceEnvelopeContentOracle180(
    senderUserId: String,
    senderDeviceId: Int,
    ciphertextType: String,
    kind: String,
    ciphertext: String,
): MessagingV2Content? {
    if (!acceptsServiceEnvelopeOracle180(senderUserId, senderDeviceId, ciphertextType)) return null
    val decoded = decodeContentOracle180(ciphertext) ?: return null
    return if (acceptsOracle180(kind, decoded)) decoded else null
}

internal fun outboxAttachmentIdsOracle180(localPayload: String): List<String> =
    decodeContentOracle180(localPayload)?.attachmentIds ?: emptyList()

// Canonical projection: field order fixed, maps keep first-seen key
// order (last value wins), reactedAt dropped (decode-time default).
internal fun escJson180(s: String): String {
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

internal fun strOrNullJson180(v: String?): String = if (v == null) "null" else escJson180(v)

internal fun longOrNullJson180(v: Long?): String = v?.toString() ?: "null"

internal fun stringListJson180(v: List<String>): String =
    "[" + v.joinToString(",") { escJson180(it) } + "]"

internal fun stringMapJson180(v: Map<String, String>): String =
    "{" + v.entries.joinToString(",") { (k, item) -> escJson180(k) + ":" + escJson180(item) } + "}"

internal fun metadataProjection180(m: MessageMeta?): String {
    if (m == null) return "null"
    return "{" +
        "\"mentions\":" + stringListJson180(m.mentions) +
        ",\"replyToId\":" + strOrNullJson180(m.replyToId) +
        ",\"forwardedFrom\":" + strOrNullJson180(m.forwardedFrom) +
        ",\"voiceTranscript\":" + strOrNullJson180(m.voiceTranscript) +
        ",\"voiceDurationMs\":" + longOrNullJson180(m.voiceDurationMs) +
        ",\"translations\":" + stringMapJson180(m.translations) +
        ",\"preferredTranslationLanguage\":" + strOrNullJson180(m.preferredTranslationLanguage) +
        ",\"aiImageAnalyses\":" + stringMapJson180(m.aiImageAnalyses) +
        ",\"preferredImageAnalysisMode\":" + strOrNullJson180(m.preferredImageAnalysisMode) +
        ",\"aiFileAnalyses\":" + stringMapJson180(m.aiFileAnalyses) +
        ",\"preferredFileAnalysisMode\":" + strOrNullJson180(m.preferredFileAnalysisMode) +
        ",\"aiFileLastQuestion\":" + strOrNullJson180(m.aiFileLastQuestion) +
        ",\"aiAssisted\":" + m.aiAssisted +
        ",\"markdown\":" + m.markdown +
        ",\"aiAssistantMode\":" + strOrNullJson180(m.aiAssistantMode) +
        ",\"fileName\":" + strOrNullJson180(m.fileName) +
        ",\"fileMimeType\":" + strOrNullJson180(m.fileMimeType) +
        ",\"fileSizeBytes\":" + longOrNullJson180(m.fileSizeBytes) +
        ",\"attachmentId\":" + strOrNullJson180(m.attachmentId) +
        ",\"attachmentKeyBase64\":" + strOrNullJson180(m.attachmentKeyBase64) +
        ",\"attachmentIvBase64\":" + strOrNullJson180(m.attachmentIvBase64) +
        ",\"attachmentCipherSha256\":" + strOrNullJson180(m.attachmentCipherSha256) +
        ",\"attachmentPlainSha256\":" + strOrNullJson180(m.attachmentPlainSha256) +
        ",\"attachmentCipherSize\":" + longOrNullJson180(m.attachmentCipherSize) +
        ",\"viewOnce\":" + m.viewOnce +
        ",\"viewOnceOpened\":" + m.viewOnceOpened +
        ",\"silent\":" + m.silent +
        ",\"spoilerMedia\":" + m.spoilerMedia +
        ",\"spoilerRevealed\":" + m.spoilerRevealed +
        ",\"inlineKeyboard\":[" + m.inlineKeyboard.joinToString(",") { row ->
            "[" + row.joinToString(",") { btn ->
                "{\"text\":" + escJson180(btn.text) +
                    ",\"callbackData\":" + escJson180(btn.callbackData) + "}"
            } + "]"
        } + "]" +
        ",\"forceReply\":" + m.forceReply +
        "}"
}

internal fun eventProjection180(e: MessagingV2Event?): String {
    if (e == null) return "null"
    return "{" +
        "\"action\":" + escJson180(e.action) +
        ",\"targetMessageId\":" + escJson180(e.targetMessageId) +
        ",\"content\":" + strOrNullJson180(e.content) +
        ",\"editedAt\":" + longOrNullJson180(e.editedAt) +
        ",\"reactionEmoji\":" + strOrNullJson180(e.reactionEmoji) +
        ",\"reactions\":[" + e.reactions.joinToString(",") { r ->
            "{\"userId\":" + escJson180(r.userId) +
                ",\"emoji\":" + escJson180(r.emoji) + "}"
        } + "]" +
        ",\"status\":" + strOrNullJson180(e.status) +
        ",\"throughMessageId\":" + strOrNullJson180(e.throughMessageId) +
        "}"
}

internal fun contentProjection180(c: MessagingV2Content?): String {
    if (c == null) return "null"
    return "{" +
        "\"version\":" + c.version +
        ",\"type\":" + escJson180(c.type) +
        ",\"body\":" + escJson180(c.body) +
        ",\"replyToMessageId\":" + strOrNullJson180(c.replyToMessageId) +
        ",\"attachmentIds\":" + stringListJson180(c.attachmentIds) +
        ",\"mentionedUserIds\":" + stringListJson180(c.mentionedUserIds) +
        ",\"expiresInSeconds\":" + c.expiresInSeconds +
        ",\"attributes\":" + stringMapJson180(c.attributes) +
        ",\"metadata\":" + metadataProjection180(c.metadata) +
        ",\"event\":" + eventProjection180(c.event) +
        "}"
}

// ---------- production ----------

internal fun decryptedEnvelopeContentProduction180(kind: String, plaintext: String): String =
    contentProjection180(decryptedEnvelopeContent(kind, plaintext))

internal fun serviceEnvelopeContentProduction180(
    senderUserId: String,
    senderDeviceId: Int,
    ciphertextType: String,
    kind: String,
    ciphertext: String,
): String = contentProjection180(
    serviceEnvelopeContent(senderUserId, senderDeviceId, ciphertextType, kind, ciphertext),
)

internal fun outboxAttachmentIdsProduction180(localPayload: String): String =
    stringListJson180(outboxAttachmentIds(localPayload))

// ---------- generators ----------

internal val V2_KIND_POOL180: List<String> = listOf(
    "DATA", "EVENT", "RECEIPT", "KEY_REQUEST", "SERVICE",
    "", "DATA ", "data", "EVENT ", "SERVICE_PLAINTEXT", "unknown", "key-request",
)

internal val V2_TYPE_POOL180: List<String> = listOf(
    "text", "image", "video", "file", "audio", "voice", "sticker",
    "location", "contact", "event", "sender-key-request", "sk-dist",
    "EVENT", "Event", "sender_key_request", "SENDER_KEY_REQUEST", "SK_DIST",
    "", " ", "text ",
)

internal val V2_BODY_POOL180: List<String> = listOf(
    "", "hello", "你好世界", "a".repeat(100), "line1\nline2",
    "tab\there", "quote\"x", "back\\slash", "emoji \uD83D\uDE00",
    " ", "  ", "\u0001", "null", "true", "0", "  padded  ",
)

internal val V2_NUMBER_POOL180: List<String> = listOf(
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

internal val V2_BOOL_POOL180: List<String> = listOf(
    "true", "false", "\"true\"", "\"True\"", "\"TRUE\"", "\"false\"",
    "\"False\"", "\"1\"", "\"0\"", "\"\"", "\" true\"", "0", "1", "null",
)

internal val V2_STRING_POOL180: List<String> = listOf(
    "\"\"", "\"x\"", "\"hello\"", "\"a b\"", "\"\\n\"", "\"\\u0041\"",
    "\"\\ud83d\\ude00\"", "\"\\ud800\"", "\"\\udc00\"", "\"\\ud800\\ud800\"",
    "\"tab\\there\"", "\"quote\\\"x\"", "\"back\\\\slash\"", "\"\\u0001\"",
    "\"\\u001f\"", "\"\\u00e9\"", "\"\\u4e2d\\u6587\"", "null", "123",
    "true", "[]", "{}", "\"unterminated", "\"bad\\qescape\"",
    "\"bad\\u12\"", "\"bad\\u12zz\"", "\"raw\nline\"", "\"raw\ttab\"",
)

internal val V2_ACTION_POOL180: List<String> = listOf(
    "EDIT", "REVOKE", "DELETE", "REACTION_SET", "REACTION_SNAPSHOT",
    "DELIVERY_RECEIPT", "READ_RECEIPT", "PLAY_RECEIPT",
    "edit", "revoke", "delete", "reaction_set", "reaction_snapshot",
    "delivery_receipt", "read_receipt", "EDIT ", "edit ", "", "unknown",
    "reaction", "snapshot",
)

internal fun randomJsonText180(rnd: Random, depth: Int): String {
    return when (rnd.nextInt(if (depth <= 0) 4 else 7)) {
        0 -> V2_NUMBER_POOL180[rnd.nextInt(V2_NUMBER_POOL180.size)]
        1 -> V2_STRING_POOL180[rnd.nextInt(V2_STRING_POOL180.size)]
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
                escJson180(key) + ":" + randomJsonText180(rnd, depth - 1)
            }
            "{" + parts.joinToString(",") + "}"
        }
        5 -> {
            val n = rnd.nextInt(0, 4)
            "[" + (0 until n).joinToString(",") { randomJsonText180(rnd, depth - 1) } + "]"
        }
        else -> "\"" + V2_BODY_POOL180[rnd.nextInt(V2_BODY_POOL180.size)]
            .replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

internal fun randomV2ContentJson180(rnd: Random): String {
    val sb = StringBuilder()
    sb.append("{")
    var first = true
    fun field(name: String, value: String) {
        if (!first) sb.append(",")
        first = false
        sb.append(escJson180(name)).append(":").append(value)
    }
    if (rnd.nextInt(10) < 9) {
        field("version", V2_NUMBER_POOL180[rnd.nextInt(V2_NUMBER_POOL180.size)])
    }
    if (rnd.nextInt(10) < 9) {
        val t = if (rnd.nextInt(5) == 0) {
            V2_STRING_POOL180[rnd.nextInt(V2_STRING_POOL180.size)]
        } else {
            escJson180(V2_TYPE_POOL180[rnd.nextInt(V2_TYPE_POOL180.size)])
        }
        field("type", t)
    }
    if (rnd.nextInt(3) == 0) field("body", escJson180(V2_BODY_POOL180[rnd.nextInt(V2_BODY_POOL180.size)]))
    if (rnd.nextInt(4) == 0) field("replyToMessageId", V2_STRING_POOL180[rnd.nextInt(V2_STRING_POOL180.size)])
    if (rnd.nextInt(4) == 0) {
        val n = rnd.nextInt(0, 3)
        field("attachmentIds", "[" + (0 until n).joinToString(",") { escJson180("att${rnd.nextInt(5)}") } + "]")
    }
    if (rnd.nextInt(4) == 0) {
        field("expiresInSeconds", V2_NUMBER_POOL180[rnd.nextInt(V2_NUMBER_POOL180.size)])
    }
    if (rnd.nextInt(4) == 0) {
        val pairs = mutableListOf<String>()
        if (rnd.nextBoolean()) pairs.add(escJson180("requestedSenderUserId") + ":" + escJson180(if (rnd.nextBoolean()) "user${rnd.nextInt(3)}" else " ".repeat(rnd.nextInt(3))))
        if (rnd.nextBoolean()) pairs.add(escJson180("failedMessageId") + ":" + escJson180("msg${rnd.nextInt(3)}"))
        if (rnd.nextBoolean()) pairs.add(escJson180("k${rnd.nextInt(3)}") + ":" + escJson180("v${rnd.nextInt(3)}"))
        field("attributes", "{" + pairs.joinToString(",") + "}")
    }
    if (rnd.nextInt(3) == 0) {
        val meta = StringBuilder()
        meta.append("{")
        var mFirst = true
        fun mField(name: String, value: String) {
            if (!mFirst) meta.append(",")
            mFirst = false
            meta.append(escJson180(name)).append(":").append(value)
        }
        if (rnd.nextBoolean()) mField("aiAssisted", V2_BOOL_POOL180[rnd.nextInt(V2_BOOL_POOL180.size)])
        if (rnd.nextBoolean()) mField("voiceDurationMs", V2_NUMBER_POOL180[rnd.nextInt(V2_NUMBER_POOL180.size)])
        if (rnd.nextBoolean()) mField("translations", "{\"en\":" + escJson180("hello") + ",\"zh\":" + escJson180("你好") + "}")
        if (rnd.nextBoolean()) mField("inlineKeyboard", "[[{\"text\":" + escJson180("ok") + ",\"callbackData\":" + escJson180("cb") + "}]]")
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
            ev.append(escJson180(name)).append(":").append(value)
        }
        if (rnd.nextInt(10) < 9) eField("action", escJson180(V2_ACTION_POOL180[rnd.nextInt(V2_ACTION_POOL180.size)]))
        if (rnd.nextInt(10) < 9) eField("targetMessageId", escJson180("msg${rnd.nextInt(5)}"))
        if (rnd.nextBoolean()) eField("content", escJson180(V2_BODY_POOL180[rnd.nextInt(V2_BODY_POOL180.size)]))
        if (rnd.nextBoolean()) eField("editedAt", V2_NUMBER_POOL180[rnd.nextInt(V2_NUMBER_POOL180.size)])
        if (rnd.nextBoolean()) eField("reactionEmoji", escJson180(if (rnd.nextBoolean()) "\uD83D\uDE00" else ""))
        if (rnd.nextBoolean()) {
            eField("reactions", "[{\"userId\":" + escJson180("u1") + ",\"emoji\":" + escJson180("\u2764") + ",\"reactedAt\":" + V2_NUMBER_POOL180[rnd.nextInt(V2_NUMBER_POOL180.size)] + "}]")
        }
        ev.append("}")
        field("event", ev.toString())
    }
    if (rnd.nextInt(6) == 0) field("unknownTop", "\"x\"")
    sb.append("}")
    var text = sb.toString()
    when (rnd.nextInt(8)) {
        1 -> text = " " + text + " "
        2 -> text = text.replaceFirst("{", "{\"type\":${escJson180("text")},")
        3 -> text = text.dropLast(1)
        4 -> text = text + "x"
        5 -> text = text.replace("\"type\"", "\"type\"", false)
        6 -> text = text.replaceFirst("}", ",}")
        7 -> text = "not json at all"
    }
    return text
}

internal data class V2Case180(
    val fn: String,
    val kind: String,
    val senderUserId: String,
    val senderDeviceId: Int,
    val ciphertextType: String,
    val text: String,
)

internal fun randomV2Case180(rnd: Random): V2Case180 {
    val fn = when (rnd.nextInt(3)) {
        0 -> "decrypted"
        1 -> "service"
        else -> "outbox"
    }
    val text = when (rnd.nextInt(4)) {
        0 -> randomV2ContentJson180(rnd)
        1 -> randomJsonText180(rnd, 3)
        2 -> V2_BODY_POOL180[rnd.nextInt(V2_BODY_POOL180.size)]
        else -> ""
    }
    return V2Case180(
        fn = fn,
        kind = V2_KIND_POOL180[rnd.nextInt(V2_KIND_POOL180.size)],
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

internal fun v2CaseWireJson180(c: V2Case180): String =
    "{\"fn\":" + escJson180(c.fn) +
        ",\"kind\":" + escJson180(c.kind) +
        ",\"senderUserId\":" + escJson180(c.senderUserId) +
        ",\"senderDeviceId\":" + c.senderDeviceId +
        ",\"ciphertextType\":" + escJson180(c.ciphertextType) +
        ",\"text\":" + escJson180(c.text) + "}"

class MessagingV2ContentFuzzTest {

    private fun checkV2Case180(c: V2Case180) {
        val oracle = when (c.fn) {
            "decrypted" -> contentProjection180(decryptedEnvelopeContentOracle180(c.kind, c.text))
            "service" -> contentProjection180(
                serviceEnvelopeContentOracle180(
                    c.senderUserId, c.senderDeviceId, c.ciphertextType, c.kind, c.text,
                ),
            )
            else -> stringListJson180(outboxAttachmentIdsOracle180(c.text))
        }
        val production = when (c.fn) {
            "decrypted" -> decryptedEnvelopeContentProduction180(c.kind, c.text)
            "service" -> serviceEnvelopeContentProduction180(
                c.senderUserId, c.senderDeviceId, c.ciphertextType, c.kind, c.text,
            )
            else -> outboxAttachmentIdsProduction180(c.text)
        }
        assertEquals(oracle, production, "v2($c)")
    }

    @Test
    fun pinnedBoundaries() {
        // Every kind against a matrix of content shapes.
        val texts = listOf(
            "",
            " ",
            "null",
            "[]",
            "{}",
            "{\"type\":\"text\"}",
            "{\"version\":1,\"type\":\"text\",\"body\":\"hi\"}",
            "{\"version\":2,\"type\":\"text\"}",
            "{\"version\":3,\"type\":\"text\"}",
            "{\"version\":0,\"type\":\"text\"}",
            "{\"version\":\"1\",\"type\":\"text\"}",
            "{\"version\":1e2,\"type\":\"text\"}",
            "{\"version\":1.0,\"type\":\"text\"}",
            "{\"type\":\"text\",\"type\":\"image\"}",
            "{\"type\":\"text\",\"unknown\":1}",
            "{\"type\":\"text\",\"body\":123}",
            "{\"type\":\"text\",\"body\":null}",
            "{\"type\":\"text\",\"attachmentIds\":[\"a\",\"b\"]}",
            "{\"type\":\"text\",\"attachmentIds\":[1]}",
            "{\"type\":\"text\",\"attachmentIds\":null}",
            "{\"type\":\"text\",\"attachmentIds\":\"a\"}",
            "{\"type\":\"text\",\"attributes\":{\"a\":\"b\",\"a\":\"c\"}}",
            "{\"type\":\"text\",\"attributes\":{\"a\":1}}",
            "{\"type\":\"text\",\"attributes\":null}",
            "{\"type\":\"text\",\"metadata\":{}}",
            "{\"type\":\"text\",\"metadata\":null}",
            "{\"type\":\"text\",\"metadata\":{\"aiAssisted\":true}}",
            "{\"type\":\"text\",\"metadata\":{\"aiAssisted\":\"True\"}}",
            "{\"type\":\"text\",\"metadata\":{\"aiAssisted\":1}}",
            "{\"type\":\"text\",\"metadata\":{\"unknown\":1}}",
            "{\"type\":\"text\",\"metadata\":{\"translations\":{\"en\":\"hi\"}}}",
            "{\"type\":\"text\",\"metadata\":{\"translations\":{\"en\":1}}}",
            "{\"type\":\"text\",\"metadata\":{\"inlineKeyboard\":[[{\"text\":\"ok\"}]]}}",
            "{\"type\":\"text\",\"metadata\":{\"inlineKeyboard\":[[{\"callbackData\":\"cb\"}]]}}",
            "{\"type\":\"text\",\"metadata\":{\"inlineKeyboard\":[[{\"text\":\"ok\",\"x\":1}]]}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"EDIT\",\"targetMessageId\":\"m1\",\"content\":\"new\",\"editedAt\":5}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"EDIT\",\"targetMessageId\":\"m1\",\"content\":\"new\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"EDIT\",\"targetMessageId\":\"m1\",\"editedAt\":5,\"reactionEmoji\":\"❤\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"EDIT\",\"targetMessageId\":\"m1\",\"content\":\"new\",\"editedAt\":5,\"reactions\":[{\"userId\":\"u\",\"emoji\":\"❤\"}]}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"REVOKE\",\"targetMessageId\":\"m1\",\"editedAt\":123}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"DELETE\",\"targetMessageId\":\"m1\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"DELETE\",\"targetMessageId\":\"m1\",\"content\":\"x\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"REACTION_SET\",\"targetMessageId\":\"m1\",\"reactionEmoji\":\"❤\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"REACTION_SET\",\"targetMessageId\":\"m1\",\"reactionEmoji\":\"❤\",\"content\":\"x\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"REACTION_SNAPSHOT\",\"targetMessageId\":\"m1\",\"reactions\":[{\"userId\":\"u\",\"emoji\":\"❤\"}]}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"DELIVERY_RECEIPT\",\"targetMessageId\":\"m1\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"READ_RECEIPT\",\"targetMessageId\":\"m1\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"PLAY_RECEIPT\",\"targetMessageId\":\"m1\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"EDIT\",\"targetMessageId\":\"\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"EDIT\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"targetMessageId\":\"m1\"}}",
            "{\"type\":\"event\",\"event\":{\"action\":\"EDIT\",\"targetMessageId\":\"m1\"}}",
            "{\"type\":\"event\",\"event\":null}",
            "{\"type\":\"event\",\"event\":{}}",
            "{\"type\":\"sender-key-request\",\"attributes\":{\"requestedSenderUserId\":\"u1\",\"failedMessageId\":\"m1\"}}",
            "{\"type\":\"sender-key-request\",\"body\":\"x\",\"attributes\":{\"requestedSenderUserId\":\"u1\",\"failedMessageId\":\"m1\"}}",
            "{\"type\":\"sender-key-request\",\"attachmentIds\":[\"a\"],\"attributes\":{\"requestedSenderUserId\":\"u1\",\"failedMessageId\":\"m1\"}}",
            "{\"type\":\"sender-key-request\",\"attributes\":{\"requestedSenderUserId\":\" \",\"failedMessageId\":\"m1\"}}",
            "{\"type\":\"sender-key-request\",\"attributes\":{\"failedMessageId\":\"m1\"}}",
            "{\"type\":\"sender-key-request\",\"attributes\":{\"requestedSenderUserId\":\"u1\"}}",
            "{\"type\":\"sk-dist\"}",
            "{\"type\":\"text\",\"event\":{\"action\":\"edit\",\"targetMessageId\":\"m1\",\"content\":\"x\"}}",
            "{\"type\":\"text\"} ",
            " {\"type\":\"text\"}",
            "{\"type\":\"text\",}",
            "{'type':'text'}",
            "{\"type\":\"text\"}x",
            "{\"type\":\"text\"",
            "{\"type\":\"te\\u0078t\"}",
            "{\"type\":\"\\ud800\"}",
            "{\"type\":\"\\ud83d\\ude00\"}",
            "{\"type\":\"a\nb\"}",
            "{\"version\":9007199254740993e0,\"type\":\"text\"}",
            "{\"version\":2147483648,\"type\":\"text\"}",
            "{\"version\":-2147483648,\"type\":\"text\"}",
            "{\"version\":1e-2,\"type\":\"text\"}",
            "{\"version\":15e-1,\"type\":\"text\"}",
            "{\"version\":1e,\"type\":\"text\"}",
            "{\"version\":1e+,\"type\":\"text\"}",
            "{\"version\":0e9999999999,\"type\":\"text\"}",
            "{\"version\":1e-9999,\"type\":\"text\"}",
            "{\"type\":\"text\",\"expiresInSeconds\":\"5\"}",
            "{\"type\":\"text\",\"expiresInSeconds\":\"1e2\"}",
            "{\"type\":\"text\",\"metadata\":{\"voiceDurationMs\":\"100\"}}",
            "{\"type\":\"text\",\"metadata\":{\"voiceDurationMs\":1e2}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"EDIT\",\"targetMessageId\":\"m1\",\"content\":\"\",\"editedAt\":\"5\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"REACTION_SET\",\"targetMessageId\":\"m1\",\"reactionEmoji\":\"" + "x".repeat(32) + "\"}}",
            "{\"type\":\"EVENT\",\"event\":{\"action\":\"REACTION_SET\",\"targetMessageId\":\"m1\",\"reactionEmoji\":\"" + "x".repeat(33) + "\"}}",
        )
        for (kind in V2_KIND_POOL180) {
            for (text in texts) {
                checkV2Case180(V2Case180("decrypted", kind, "bot_1", 0, "SERVICE_PLAINTEXT", text))
            }
        }
        // Service-envelope gate matrix.
        val senders = listOf("bot_1", "bot_", "system", "bot", "user1", "", "system2", "bot_x")
        val devices = listOf(0, 1, -1)
        val ctypes = listOf("SERVICE_PLAINTEXT", "SERVICE_CIPHERTEXT", "", "service_plaintext")
        val svcText = "{\"version\":1,\"type\":\"text\",\"body\":\"hi\"}"
        for (sender in senders) {
            for (device in devices) {
                for (ctype in ctypes) {
                    checkV2Case180(V2Case180("service", "SERVICE", sender, device, ctype, svcText))
                    checkV2Case180(V2Case180("service", "DATA", sender, device, ctype, svcText))
                }
            }
        }
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(180001)
        repeat(6000) { checkV2Case180(randomV2Case180(rnd)) }
    }

    @Test
    fun sweepExhaustive() {
        // Every kind × every type × every action, with/without event.
        val kinds = V2_KIND_POOL180
        val types = V2_TYPE_POOL180
        val actions = V2_ACTION_POOL180
        for (kind in kinds) {
            for (type in types) {
                val base = "{\"version\":1,\"type\":" + escJson180(type) + "}"
                checkV2Case180(V2Case180("decrypted", kind, "bot_1", 0, "SERVICE_PLAINTEXT", base))
                for (action in actions) {
                    val withEvent = "{\"version\":1,\"type\":" + escJson180(type) +
                        ",\"event\":{\"action\":" + escJson180(action) +
                        ",\"targetMessageId\":\"m1\",\"content\":\"x\",\"editedAt\":5," +
                        "\"reactionEmoji\":\"❤\",\"reactions\":[{\"userId\":\"u\",\"emoji\":\"❤\"}]}}"
                    checkV2Case180(V2Case180("decrypted", kind, "bot_1", 0, "SERVICE_PLAINTEXT", withEvent))
                }
            }
        }
        // Key-request attribute lengths around the 50/100 limits.
        for (reqLen in listOf(0, 1, 49, 50, 51, 100)) {
            for (failLen in listOf(0, 1, 99, 100, 101)) {
                val text = "{\"version\":1,\"type\":\"sender-key-request\",\"attributes\":{" +
                    "\"requestedSenderUserId\":" + escJson180("u".repeat(reqLen)) +
                    ",\"failedMessageId\":" + escJson180("m".repeat(failLen)) + "}}"
                checkV2Case180(V2Case180("decrypted", "KEY_REQUEST", "bot_1", 0, "SERVICE_PLAINTEXT", text))
            }
        }
    }
}
