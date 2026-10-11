package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.ConversationQueryRepository
import com.maodouchat.server.repository.UserRepository
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json

/**
 * 投票 legacy 路由父门面：按域拆为查询簇（列表/详情）与写入簇（创建/投票/关闭），零行为改动。
 */
internal fun Route.configurePollLegacyRoutes(
    userRepo: UserRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    json: Json,
) {
    configurePollLegacyQueryRoutes()
    configurePollLegacyManageRoutes(userRepo)
}
