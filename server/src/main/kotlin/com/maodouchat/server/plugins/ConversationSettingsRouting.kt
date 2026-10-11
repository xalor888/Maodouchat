package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.ConversationCommandService
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/** 聊天设置路由门面：按域拆为通用设置 / 阅后即焚两簇，零行为改动。 */
internal fun Route.configureConversationSettingsRoutes(
    userRepo: UserRepository,
    commandService: ConversationCommandService,
    participantRepository: ConversationParticipantRepository,
    json: Json,
) {
    configureConversationSettingsGeneralRoutes(commandService)
    configureConversationSettingsDisappearingRoutes(
        userRepo = userRepo,
        commandService = commandService,
        participantRepository = participantRepository,
        json = json,
    )
}
