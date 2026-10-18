package com.maodouchat.server.plugins

import com.maodouchat.server.messaging.v2.MessagingV2Repository
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.ConversationCommandService
import com.maodouchat.server.service.ConversationCreationService
import com.maodouchat.server.service.GroupInvitationService
import com.maodouchat.server.service.GroupMembershipService
import com.maodouchat.server.service.MediaReferenceService
import com.maodouchat.server.service.OrphanGcJob
import com.maodouchat.server.service.SessionService

// 路由层 repository/service 的一次性装配：纯构造、无逻辑，Routing.kt 只做接线。
internal class RoutingServiceAssembly(
    val signalKeyRepo: SignalKeyRepository,
    val starMessageRepo: StarMessageRepository,
    val pinnedMessageRepo: PinnedMessageRepository,
    val serviceMessageRepo: ServiceMessageRepository,
    val authTokenRepo: AuthTokenRepository,
    val sessionService: SessionService,
    val friendRepo: FriendRepository,
    val chatFolderRepo: ChatFolderRepository,
    val clientPrefsRepo: ClientPrefsRepository,
    val aiRepo: AiRepository,
    val encryptedAttachmentRepo: EncryptedAttachmentRepository,
    val senderKeyDistributionRepo: SenderKeyDistributionRepository,
    val reportRepo: ReportWorkflow,
    val groupMembershipRepo: GroupMembershipRepository,
    val groupMembershipService: GroupMembershipService,
    val groupProfileRepo: GroupProfileRepository,
    val groupModerationRepo: GroupModerationRepository,
    val groupInvitationRepo: GroupInvitationRepository,
    val groupInvitationService: GroupInvitationService,
    val conversationLifecycleRepo: ConversationLifecycleRepository,
    val conversationCreationRepo: ConversationCreationRepository,
    val conversationCreationService: ConversationCreationService,
    val conversationSettingsRepo: ConversationSettingsRepository,
    val conversationCommandService: ConversationCommandService,
    val conversationParticipantRepo: ConversationParticipantRepository,
    val conversationQueryRepo: ConversationQueryRepository,
    val groupAuditRepo: GroupAuditRepository,
    val groupMediaReferenceRepo: GroupMediaReferenceRepository,
    val mediaReferenceService: MediaReferenceService,
    val orphanGcJob: OrphanGcJob,
    val moderationRuleRepo: ModerationRuleRepository,
    val nearbyRepo: NearbyRepository,
)

internal fun buildRoutingServiceAssembly(
    postRepo: PostRepository,
    messagingV2Repository: MessagingV2Repository,
    pushTokenRepo: PushTokenRepository,
): RoutingServiceAssembly {
    val signalKeyRepo = SignalKeyRepository()
    val starMessageRepo = StarMessageRepository()
    val pinnedMessageRepo = PinnedMessageRepository()
    val serviceMessageRepo = ServiceMessageRepository(messagingV2Repository)
    val authTokenRepo = AuthTokenRepository()
    val sessionService = SessionService(authTokenRepo, pushTokenRepo)
    val friendRepo = FriendRepository()
    val chatFolderRepo = ChatFolderRepository()
    val clientPrefsRepo = ClientPrefsRepository()
    val aiRepo = AiRepository()
    val encryptedAttachmentRepo = EncryptedAttachmentRepository()
    val senderKeyDistributionRepo = SenderKeyDistributionRepository()
    val reportRepo = ReportWorkflow()
    val groupMembershipRepo = GroupMembershipRepository()
    val groupMembershipService = GroupMembershipService(groupMembershipRepo)
    val groupProfileRepo = GroupProfileRepository()
    val groupModerationRepo = GroupModerationRepository()
    val groupInvitationRepo = GroupInvitationRepository()
    val groupInvitationService = GroupInvitationService(groupInvitationRepo)
    val conversationLifecycleRepo = ConversationLifecycleRepository()
    val conversationCreationRepo = ConversationCreationRepository()
    val conversationCreationService = ConversationCreationService(
        conversationCreationRepo,
        groupInvitationService,
    )
    val conversationSettingsRepo = ConversationSettingsRepository()
    val conversationCommandService = ConversationCommandService(
        creationService = conversationCreationService,
        lifecycleRepository = conversationLifecycleRepo,
        settingsRepository = conversationSettingsRepo,
    )
    val conversationParticipantRepo = ConversationParticipantRepository()
    val conversationQueryRepo = ConversationQueryRepository()
    val groupAuditRepo = GroupAuditRepository()
    val groupMediaReferenceRepo = GroupMediaReferenceRepository()
    val mediaReferenceService = MediaReferenceService(encryptedAttachmentRepo, groupMediaReferenceRepo)
    val orphanGcJob = OrphanGcJob(
        encryptedAttachmentRepo,
        postRepo,
        mediaReferenceService,
    )
    val moderationRuleRepo = ModerationRuleRepository()
    val nearbyRepo = NearbyRepository()
    return RoutingServiceAssembly(
        signalKeyRepo = signalKeyRepo,
        starMessageRepo = starMessageRepo,
        pinnedMessageRepo = pinnedMessageRepo,
        serviceMessageRepo = serviceMessageRepo,
        authTokenRepo = authTokenRepo,
        sessionService = sessionService,
        friendRepo = friendRepo,
        chatFolderRepo = chatFolderRepo,
        clientPrefsRepo = clientPrefsRepo,
        aiRepo = aiRepo,
        encryptedAttachmentRepo = encryptedAttachmentRepo,
        senderKeyDistributionRepo = senderKeyDistributionRepo,
        reportRepo = reportRepo,
        groupMembershipRepo = groupMembershipRepo,
        groupMembershipService = groupMembershipService,
        groupProfileRepo = groupProfileRepo,
        groupModerationRepo = groupModerationRepo,
        groupInvitationRepo = groupInvitationRepo,
        groupInvitationService = groupInvitationService,
        conversationLifecycleRepo = conversationLifecycleRepo,
        conversationCreationRepo = conversationCreationRepo,
        conversationCreationService = conversationCreationService,
        conversationSettingsRepo = conversationSettingsRepo,
        conversationCommandService = conversationCommandService,
        conversationParticipantRepo = conversationParticipantRepo,
        conversationQueryRepo = conversationQueryRepo,
        groupAuditRepo = groupAuditRepo,
        groupMediaReferenceRepo = groupMediaReferenceRepo,
        mediaReferenceService = mediaReferenceService,
        orphanGcJob = orphanGcJob,
        moderationRuleRepo = moderationRuleRepo,
        nearbyRepo = nearbyRepo,
    )
}
