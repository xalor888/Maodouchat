package com.maodouchat.server.plugins

import com.maodouchat.server.repository.UserRepository
import com.maodouchat.server.service.GroupMembershipService
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.put
import kotlinx.serialization.json.Json

/** 群成员角色与转让。 */
internal fun Route.configureGroupAdminMembershipRoleRoutes(
    userRepo: UserRepository,
    membershipService: GroupMembershipService,
    json: Json,
) {
    authenticate("auth-jwt") {

        put("/api/chats/{chatId}/members/{memberId}/role") {
            call.handleUpdateGroupMemberRole(userRepo, membershipService, json)
        }


        put("/api/chats/{chatId}/members/{memberId}/ownership") {
            call.handleTransferGroupOwnership(userRepo, membershipService, json)
        }
    }
}
