package com.maodouchat.server.plugins

import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.delete
import kotlinx.serialization.json.Json

/** 群成员移除。 */
internal fun Route.configureGroupAdminMembershipRemoveRoutes(
    userRepo: UserRepository,
    membershipService: GroupMembershipService,
    json: Json,
) {
    authenticate("auth-jwt") {

        delete("/api/chats/{chatId}/members/{memberId}") {
            call.handleRemoveGroupMember(userRepo, membershipService, json)
        }
    }
}
