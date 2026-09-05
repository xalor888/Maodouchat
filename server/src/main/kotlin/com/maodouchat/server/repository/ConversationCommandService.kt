package com.maodouchat.server.repository

import com.maodouchat.server.model.UpdateChatSettingsRequest

/**
 * 会话领域统一命令服务 (B04)。
 * 统一收敛 direct/group/channel 的创建、退出、设置与销毁命令，
 * 消除路由层对多个底层 repository 的裸露调用与业务逻辑渗透。
 */
class ConversationCommandService(
    private val creationService: ConversationCreationService = ConversationCreationService(
        creationRepository = ConversationCreationRepository(),
        invitationService = GroupInvitationService()
    ),
    private val lifecycleRepository: ConversationLifecycleRepository = ConversationLifecycleRepository(),
    private val settingsRepository: ConversationSettingsRepository = ConversationSettingsRepository()
) {

    /** 创建会话（支持单聊、密聊、频道、群聊）。 */
    fun create(
        actorId: String,
        command: CreateConversationCommand,
        maxGroupMembers: Int,
        maxChannelMembers: Int
    ): CreateConversationOutcome = creationService.create(actorId, command, maxGroupMembers, maxChannelMembers)

    /** 退出会话（普通成员退群、所有者解散频道、双人会话退出）。 */
    fun leave(
        chatId: String,
        userId: String,
        requireBotDeliverable: Boolean = false
    ): LeaveConversationOutcome = lifecycleRepository.leave(chatId, userId, requireBotDeliverable)

    /** 幂等打开或创建 1:1 会话（机器人私聊等人机直达入口；重复调用返回同一会话）。 */
    fun getOrCreateDirect(userId1: String, userId2: String): CreatedConversation =
        creationService.getOrCreateDirect(userId1, userId2)

    /** 更新用户在指定会话中的个性化配置（置顶、静音、文件夹、草稿等）。 */
    fun updateSettings(
        chatId: String,
        userId: String,
        request: UpdateChatSettingsRequest
    ): ChatSettingsMutationOutcome = settingsRepository.updateUserSettings(chatId, userId, request)

    /** 更新单聊阅后即焚 (TTL) 时长。 */
    fun setDisappearingMessages(
        chatId: String,
        userId: String,
        ttlSeconds: Int
    ): DisappearingMessagesMutationOutcome = settingsRepository.setDisappearingMessages(chatId, userId, ttlSeconds)
}
