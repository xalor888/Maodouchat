package com.maodouchat.util

import com.maodouchat.data.model.MessageMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把任何 Map/List/标量 转成紧凑 JSON 字符串（无 indent）
 */
object JsonFormat {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun encode(value: Any?): String = json.encodeToString(JsonElement.serializer(), toJsonElement(value))
    fun encodePretty(value: Any?): String = prettyJson.encodeToString(JsonElement.serializer(), toJsonElement(value))

    /** Encode MessageMeta as a JSON object (never data-class toString()). */
    fun encodeMessageMeta(meta: MessageMeta): String = encode(messageMetaMap(meta))

    fun messageMetaMap(meta: MessageMeta): Map<String, Any?> = mapOf(
        "mentions" to meta.mentions,
        "replyToId" to meta.replyToId,
        // 9.143：forwardedFrom 此前漏在编解码两侧——转发来源显示名在
        // composeContentWithMeta 编码时被丢弃，收件方「转发自 X」永不显示
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

    fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> value
        is MessageMeta -> toJsonElement(messageMetaMap(value))
        is Map<*, *> -> JsonObject(
            value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) }
        )
        is List<*> -> kotlinx.serialization.json.JsonArray(value.map { toJsonElement(it) })
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    /** 从一段 JSON 字符串解析出 MessageMeta */
    fun fromJsonString(text: String): MessageMeta {
        if (text.isBlank()) return MessageMeta()
        val element = json.parseToJsonElement(text).jsonObject
        val mentions = element["mentions"]?.jsonArray?.mapNotNull { it.asStringOrNull() } ?: emptyList()
        val replyToId = element["replyToId"]?.asStringOrNull()
        // 9.143：与 messageMetaMap 配对，恢复转发来源显示名
        val forwardedFrom = element["forwardedFrom"]?.asStringOrNull()
        val voiceTranscript = element["voiceTranscript"]?.asStringOrNull()
        val voiceDurationMs = element["voiceDurationMs"]?.asStringOrNull()?.toLongOrNull()
        val translations = element["translations"]?.jsonObject
            ?.mapValues { (_, value) -> value.asStringOrNull().orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val preferredTranslationLanguage = element["preferredTranslationLanguage"]
            ?.asStringOrNull()
        val aiImageAnalyses = element["aiImageAnalyses"]?.jsonObject
            ?.mapValues { (_, value) -> value.asStringOrNull().orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val preferredImageAnalysisMode = element["preferredImageAnalysisMode"]
            ?.asStringOrNull()
        val aiFileAnalyses = element["aiFileAnalyses"]?.jsonObject
            ?.mapValues { (_, value) -> value.asStringOrNull().orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val preferredFileAnalysisMode = element["preferredFileAnalysisMode"]
            ?.asStringOrNull()
        val aiFileLastQuestion = element["aiFileLastQuestion"]
            ?.asStringOrNull()
        val aiAssisted = element["aiAssisted"]
            ?.asStringOrNull()
            ?.toBooleanStrictOrNull()
            ?: false
        val aiAssistantMode = element["aiAssistantMode"]
            ?.asStringOrNull()
        val fileName = element["fileName"]?.asStringOrNull()
        val fileMimeType = element["fileMimeType"]?.asStringOrNull()
        val fileSizeBytes = element["fileSizeBytes"]?.asStringOrNull()?.toLongOrNull()
        val attachmentId = element["attachmentId"]?.asStringOrNull()
        val attachmentKeyBase64 = element["attachmentKeyBase64"]?.asStringOrNull()
        val attachmentIvBase64 = element["attachmentIvBase64"]?.asStringOrNull()
        val attachmentCipherSha256 = element["attachmentCipherSha256"]?.asStringOrNull()
        val attachmentPlainSha256 = element["attachmentPlainSha256"]?.asStringOrNull()
        val attachmentCipherSize = element["attachmentCipherSize"]?.asStringOrNull()?.toLongOrNull()
        val markdown = element["markdown"]?.asStringOrNull()?.toBooleanStrictOrNull() ?: false
        val viewOnce = element["viewOnce"]?.asStringOrNull()?.toBooleanStrictOrNull() ?: false
        val viewOnceOpened = element["viewOnceOpened"]?.asStringOrNull()?.toBooleanStrictOrNull() ?: false
        val silent = element["silent"]?.asStringOrNull()?.toBooleanStrictOrNull() ?: false
        val spoilerMedia = element["spoilerMedia"]?.asStringOrNull()?.toBooleanStrictOrNull() ?: false
        val spoilerRevealed = element["spoilerRevealed"]?.asStringOrNull()?.toBooleanStrictOrNull() ?: false
        val forceReply = element["forceReply"]?.asStringOrNull()?.toBooleanStrictOrNull() ?: false
        val inlineKeyboard = element["inlineKeyboard"]?.jsonArray?.mapNotNull { rowEl ->
            val rowArr = rowEl as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            rowArr.mapNotNull { btnEl ->
                val o = btnEl as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val t = o["text"]?.asStringOrNull().orEmpty()
                val d = o["callbackData"]?.asStringOrNull()
                    ?: o["callback_data"]?.asStringOrNull().orEmpty()
                if (t.isBlank()) null
                else com.maodouchat.data.model.InlineKeyboardButton(text = t.take(64), callbackData = d.take(128))
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

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { contentOrNull }.getOrNull()

    /**
     * 安全地从任意 JsonElement 取字符串：JsonPrimitive（含 JsonNull，其 contentOrNull 为 null）
     * 返回 contentOrNull；非基础类型（对象/数组）返回 null，绝不抛 IllegalStateException。
     * 用于防御畸形 meta —— 单字段损坏不应导致整段 MessageMeta（含附件解密密钥）被丢弃。
     */
    private fun JsonElement.asStringOrNull(): String? =
        if (this is JsonPrimitive) contentOrNull else null
}
