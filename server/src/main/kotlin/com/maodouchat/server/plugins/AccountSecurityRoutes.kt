package com.maodouchat.server.plugins

import com.maodouchat.server.repository.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/** 账号安全：改密/注销。 */
internal fun Route.configureAccountSecurityRoutes(
    userRepo: UserRepository,
    postRepo: PostRepository,
    sessionService: com.maodouchat.server.service.SessionService,
    groupMediaReferenceRepo: GroupMediaReferenceRepository,
    encryptedAttachmentRepo: EncryptedAttachmentRepository,
    conversationParticipantRepo: ConversationParticipantRepository,
    conversationQueryRepo: ConversationQueryRepository,
    json: Json,
) {
    configureAccountSecurityPasswordRoutes(userRepo, sessionService)
    configureAccountSecurityDeleteRoutes(
        userRepo,
        postRepo,
        sessionService,
        groupMediaReferenceRepo,
        encryptedAttachmentRepo,
        conversationParticipantRepo,
        conversationQueryRepo,
        json,
    )
}
