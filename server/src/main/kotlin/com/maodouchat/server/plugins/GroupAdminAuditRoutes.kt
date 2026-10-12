package com.maodouchat.server.plugins

import com.maodouchat.server.repository.ConversationParticipantRepository
import com.maodouchat.server.repository.GroupAuditRepository
import com.maodouchat.server.repository.SenderKeyDistributionRepository
import com.maodouchat.server.repository.SignalKeyRepository
import io.ktor.server.routing.Route

/** 群管理路由：查询与审计（成员列表/操作记录/密钥分发状态）。 */
internal fun Route.configureGroupAdminAuditRoutes(
    participantRepository: ConversationParticipantRepository,
    auditRepository: GroupAuditRepository,
    signalKeyRepository: SignalKeyRepository,
    senderKeyRepository: SenderKeyDistributionRepository,
) {
    configureGroupAdminAuditMemberRoutes(
        participantRepository = participantRepository,
    )
    configureGroupAdminAuditLogRoutes(
        participantRepository = participantRepository,
        auditRepository = auditRepository,
        signalKeyRepository = signalKeyRepository,
        senderKeyRepository = senderKeyRepository,
    )
}
