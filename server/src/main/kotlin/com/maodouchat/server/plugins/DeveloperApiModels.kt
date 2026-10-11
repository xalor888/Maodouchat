package com.maodouchat.server.plugins

import com.maodouchat.server.repository.BotRepository
import kotlinx.serialization.Serializable

// Developer API 响应模型（纯搬移，零行为改动）。

// ─── Response data classes ─────────────────────────

@Serializable
data class CapabilityManifestResponse(
    val version: String,
    val messaging: MessagingCapabilities,
    val groups: GroupCapabilities,
    val ai: AiCapabilities,
    val integrations: IntegrationCapabilities
)

@Serializable
data class MessagingCapabilities(
    val canSendMessage: Boolean,
    val canSendImages: Boolean,
    val canSendVideos: Boolean,
    val canSendFiles: Boolean,
    val canSendVoice: Boolean,
    val canSendMarkdown: Boolean,
    val maxMessageLength: Int,
    val supportsReply: Boolean,
    val supportsForward: Boolean,
    val supportsPin: Boolean,
    val supportsEdit: Boolean,
    val supportsRevoke: Boolean,
    val supportsReaction: Boolean
)

@Serializable
data class GroupCapabilities(
    val canJoinGroups: Boolean,
    val canCreateGroups: Boolean,
    val maxGroupSize: Int,
    val canReadGroupHistory: Boolean,
    val canManageMembers: Boolean,
    val supportsGroupPlay: Boolean,
    val supportsPolls: Boolean
)

@Serializable
data class AiCapabilities(
    val clientAiEnabled: Boolean,
    val contentModerationEnabled: Boolean
)

@Serializable
data class IntegrationCapabilities(
    val webhookSupported: Boolean,
    val webhookMaxRetries: Int,
    val webhookTimeoutSeconds: Int,
    val supportedUpdateTypes: List<String>,
    val maxCommands: Int
)

// ═══ Developer-account response types ═══

@Serializable
data class DevLoginResponse(
    val requiresTotp: Boolean = false,
    val token: String,
    val userId: String,
    val email: String,
    val name: String = "",
    val bots: List<BotRepository.BotDto>
)

@Serializable
data class DevMeResponse(
    val userId: String,
    val email: String,
    val name: String,
    val bots: List<BotRepository.BotDto>
)
