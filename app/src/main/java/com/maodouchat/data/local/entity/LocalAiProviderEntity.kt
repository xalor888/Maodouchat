package com.maodouchat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import com.maodouchat.domain.messaging.LocalAiProtocolCodec
import com.maodouchat.domain.messaging.LocalAiProvider

/**
 * 用户自配模型端点（退役 `maodou_local_ai-<userId>` 加密 prefs 里的 providers_json）。
 * 全部字段按列存（API Key 也在 SQLCipher 加密库内）；只有列表顺序用 sortOrder 存，
 * 与旧 JSON 数组顺序一致（upsert 时新/改的排最后）。
 */
@Entity(
    tableName = "local_ai_providers",
    primaryKeys = ["ownerUserId", "id"],
    indices = [
        Index(value = ["ownerUserId"]),
    ],
)
data class LocalAiProviderEntity(
    val ownerUserId: String,
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** 枚举名原文；解析走 LocalAiProtocolCodec，未知值回落为 OPENAI_CHAT_COMPLETIONS。 */
    val protocol: String,
    val anthropicVersion: String,
    val organization: String,
    val extraHeadersJson: String,
    val temperature: Double?,
    val topP: Double?,
    val maxTokens: Int,
    val contextWindowTokens: Int,
    val historyMessageLimit: Int,
    val timeoutSeconds: Int,
    val stream: Boolean,
    val supportsVision: Boolean,
    val sortOrder: Int,
)

fun LocalAiProvider.toEntity(ownerUserId: String, sortOrder: Int): LocalAiProviderEntity =
    LocalAiProviderEntity(
        ownerUserId = ownerUserId,
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        protocol = protocol.name,
        anthropicVersion = anthropicVersion,
        organization = organization,
        extraHeadersJson = extraHeadersJson,
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        contextWindowTokens = contextWindowTokens,
        historyMessageLimit = historyMessageLimit,
        timeoutSeconds = timeoutSeconds,
        stream = stream,
        supportsVision = supportsVision,
        sortOrder = sortOrder,
    )

fun LocalAiProviderEntity.toModel(): LocalAiProvider =
    LocalAiProvider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        protocol = LocalAiProtocolCodec.parseProtocol(protocol),
        anthropicVersion = anthropicVersion,
        organization = organization,
        extraHeadersJson = extraHeadersJson,
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
        contextWindowTokens = contextWindowTokens,
        historyMessageLimit = historyMessageLimit,
        timeoutSeconds = timeoutSeconds,
        stream = stream,
        supportsVision = supportsVision,
    )
