package com.maodouchat.ui.screen.chatlist

import android.app.Application
import androidx.room.withTransaction
import com.maodouchat.MaodouchatApp
import com.maodouchat.R
import com.maodouchat.ai.AiArchiveSuggestion
import com.maodouchat.conversation.ConversationLocalStateCoordinator
import com.maodouchat.conversation.createAndroidConversationLocalStateCoordinator
import com.maodouchat.core.realtime.RealtimeEventDispatcher
import com.maodouchat.crypto.PersistentSignalProtocolStore
import com.maodouchat.data.local.entity.ChatDraftEntity
import com.maodouchat.data.model.Chat
import com.maodouchat.data.repository.AnnouncementNetworkRepository
import com.maodouchat.data.repository.ChatNetworkRepository
import com.maodouchat.data.repository.ChatRepository
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.data.repository.MissedCallRepository
import com.maodouchat.data.repository.PushNetworkRepository
import com.maodouchat.data.repository.NotificationCenterRepository
import com.maodouchat.data.repository.SecretChatRepository
import com.maodouchat.messaging.v2.MessagingV2Outbox
import com.maodouchat.network.ApiService
import com.maodouchat.network.ChatDto
import com.maodouchat.network.ChatSettingsResponse
import com.maodouchat.network.TokenManager
import com.maodouchat.network.UpdateChatSettingsRequest
import com.maodouchat.notification.CallNotificationService
import com.maodouchat.notification.MessageNotificationService
import com.maodouchat.scheduling.AndroidConversationScheduleBackend
import com.maodouchat.scheduling.ConversationScheduleCoordinator
import com.maodouchat.security.SecureSessionManager
import com.maodouchat.sync.BacklogSyncWorker
import com.maodouchat.util.PushVerifyPrefs
import com.maodouchat.util.RuntimeFlags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * ChatList 基础设施端口袋：ViewModel 只消费本袋，生产侧全局单例/DAO/Api 仅在
 * [AndroidChatListPorts.create] 装配。
 */
internal class ChatListPorts(
    val tokenManager: TokenManager,
    val chatRepository: ChatRepository,
    val messageStore: LocalMessageStore,
    val missedCallRepository: MissedCallRepository,
    val notificationCenter: NotificationCenterRepository,
    val scheduleCoordinator: ConversationScheduleCoordinator,
    val conversationLocalStateCoordinator: ConversationLocalStateCoordinator,
    val realtimeEventDispatcher: RealtimeEventDispatcher,
    val sessionGeneration: () -> Long,
    val activeChatId: () -> String?,
    val isPurgeInProgress: () -> Boolean,
    val chatReadEvents: SharedFlow<MaodouchatApp.Companion.ChatReadEvent>,
    val chatMessageSentEvents: SharedFlow<MaodouchatApp.Companion.ChatMessageSentEvent>,
    val withRoomTransaction: suspend (block: suspend () -> Boolean) -> Boolean,
    val fetchRemoteChats: suspend (token: String) -> Result<List<ChatDto>>,
    val fetchActiveAnnouncements: suspend (token: String) -> Result<String>,
    val ackAnnouncementRemote: suspend (token: String, announcementId: String) -> Result<*>,
    val fetchPushVerifyKeyRaw: suspend (token: String) -> Result<String>,
    val applyPushVerifyKey: (raw: String) -> Unit,
    val updateChatSettingsRemote: suspend (
        token: String,
        chatId: String,
        request: UpdateChatSettingsRequest,
    ) -> Result<ChatSettingsResponse>,
    val deleteChatRemote: suspend (token: String, chatId: String) -> Result<Unit>,
    val createChatRemote: suspend (
        token: String,
        peerIds: List<String>,
        isGroup: Boolean,
        groupName: String?,
        chatType: String?,
    ) -> Result<ChatDto>,
    val touchSecretChat: suspend (chatId: String) -> Unit,
    val cancelMessageNotification: (chatId: String) -> Unit,
    val cancelMissedCallNotification: (callId: String) -> Unit,
    val markChatMessagesRead: (chatId: String) -> Unit,
    val secretChatFeatureEnabled: () -> Boolean,
    val secretChatDisabledMessage: () -> String,
    val secretChatStartFailedMessage: () -> String,
    val applyRealtimeVisibility: suspend (
        userId: String,
        isOnline: Boolean,
        onlineRevoked: Boolean,
        statusRevoked: Boolean,
        updatedAt: Long,
    ) -> Unit,
    val requestBacklogSync: () -> Unit,
    val loadDismissedArchiveIds: suspend (ownerUserId: String) -> List<String>,
    val addArchiveDismissal: suspend (ownerUserId: String, chatId: String, atMillis: Long) -> Unit,
    val refreshArchiveSuggestions: suspend () -> List<AiArchiveSuggestion.Suggestion>,
    val enqueueReadReceipt: suspend (
        conversationId: String,
        throughMessageId: String,
        groupRevision: Long?,
    ) -> Unit,
    val observeDraftsForOwner: (ownerUserId: String) -> Flow<List<ChatDraftEntity>>,
    val deleteDraftForChat: suspend (ownerUserId: String, chatId: String) -> Unit,
    val listLockedChatIds: suspend () -> Set<String>,
    val searchChatIdsByMessageContent: suspend (escaped: String) -> List<String>,
    val listSecretChatIds: suspend () -> Set<String>,
    val trustChangedRemoteIds: suspend (owner: String, remoteIds: Collection<String>) -> Set<String>,
    val isFlagEnabled: (RuntimeFlags.Flag) -> Boolean,
)

/** 生产装配：唯一允许触碰 MaodouchatApp / ApiService / DAO 的 ChatList 入口。 */
internal object AndroidChatListPorts {
    fun create(application: Application): ChatListPorts {
        val app = application as MaodouchatApp
        val database = app.database
        val tokenManager = TokenManager.getInstance(application)
        val chatRepository = ChatRepository(database.chatDao(), database.userDao())
        val messageStore = LocalMessageStore(database.messageDao(), database)
        val missedCallRepository = MissedCallRepository(database.missedCallDao())
        val scheduleCoordinator = ConversationScheduleCoordinator(
            ownerUserId = { tokenManager.getUserId().orEmpty() },
            backend = AndroidConversationScheduleBackend(application),
        )
        val conversationLocalStateCoordinator = createAndroidConversationLocalStateCoordinator(
            app = app,
            tokenManager = tokenManager,
            scheduleCoordinator = scheduleCoordinator,
        )
        val outbox: MessagingV2Outbox = app.messagingV2Outbox
        // G328c：下面这七处调用此前直接打 ApiService；现在统一经 data 层仓库。
        // 三个都是无状态的薄包装，只在这次装配里用到，所以是局部值而不是字段。
        val chatNetwork = ChatNetworkRepository()
        val announcements = AnnouncementNetworkRepository()
        val push = PushNetworkRepository()
        return ChatListPorts(
            tokenManager = tokenManager,
            chatRepository = chatRepository,
            messageStore = messageStore,
            missedCallRepository = missedCallRepository,
            notificationCenter = app.notificationCenter,
            scheduleCoordinator = scheduleCoordinator,
            conversationLocalStateCoordinator = conversationLocalStateCoordinator,
            realtimeEventDispatcher = app.realtimeEventDispatcher,
            sessionGeneration = { MaodouchatApp.currentSessionGeneration() },
            activeChatId = { MaodouchatApp.activeChatId ?: MaodouchatApp.openChatDetailId },
            isPurgeInProgress = { SecureSessionManager.isPurgeInProgress() },
            chatReadEvents = MaodouchatApp.chatReadEvents,
            chatMessageSentEvents = MaodouchatApp.chatMessageSentEvents,
            withRoomTransaction = { block -> database.withTransaction { block() } },
            fetchRemoteChats = { token -> chatNetwork.chats(token) },
            fetchActiveAnnouncements = { token -> announcements.active(token) },
            ackAnnouncementRemote = { token, id -> announcements.ack(token, id) },
            fetchPushVerifyKeyRaw = { token -> push.verifyKey(token) },
            applyPushVerifyKey = { raw ->
                when (val action = parsePushVerifyKeyPayload(raw)) {
                    PushVerifyKeyAction.Clear -> PushVerifyPrefs.clearKey(application)
                    is PushVerifyKeyAction.Set -> PushVerifyPrefs.setKey(application, action.key)
                    PushVerifyKeyAction.Ignore -> Unit
                }
            },
            updateChatSettingsRemote = { token, chatId, request ->
                chatNetwork.updateChatSettings(token, chatId, request)
            },
            deleteChatRemote = { token, chatId -> chatNetwork.deleteChat(token, chatId) },
            createChatRemote = { token, peerIds, isGroup, groupName, chatType ->
                chatNetwork.createChat(token, peerIds, isGroup, groupName, chatType)
            },
            touchSecretChat = { chatId ->
                SecretChatRepository(database.secretChatDao()).touch(chatId)
            },
            cancelMessageNotification = { chatId ->
                MessageNotificationService.cancelMessage(app, chatId)
            },
            cancelMissedCallNotification = { callId ->
                CallNotificationService.cancelMissedCall(app, callId)
            },
            markChatMessagesRead = { chatId ->
                app.notificationCenter.markChatMessagesRead(chatId)
            },
            secretChatFeatureEnabled = {
                RuntimeFlags.isEnabled(app, RuntimeFlags.SECRET_CHAT)
            },
            secretChatDisabledMessage = { app.getString(R.string.secret_chat_feature_disabled) },
            secretChatStartFailedMessage = { app.getString(R.string.secret_chat_start_failed) },
            applyRealtimeVisibility = { userId, isOnline, onlineRevoked, statusRevoked, updatedAt ->
                database.userDao().applyRealtimeVisibility(
                    userId = userId,
                    isOnline = isOnline,
                    onlineRevoked = onlineRevoked,
                    statusRevoked = statusRevoked,
                    updatedAt = updatedAt,
                )
            },
            requestBacklogSync = {
                runCatching { BacklogSyncWorker.requestNow(application) }
            },
            loadDismissedArchiveIds = { userId ->
                database.archiveDismissalDao().dismissedIds(userId)
            },
            addArchiveDismissal = { userId, chatId, atMillis ->
                database.archiveDismissalDao().add(
                    ChatListArchiveSuggestionCoordinator.dismissalEntity(userId, chatId, atMillis),
                )
            },
            refreshArchiveSuggestions = {
                AiArchiveSuggestion.refresh(application, database)
            },
            enqueueReadReceipt = { conversationId, throughMessageId, groupRevision ->
                outbox.enqueueReadReceipt(
                    conversationId = conversationId,
                    throughMessageId = throughMessageId,
                    groupRevision = groupRevision,
                )
            },
            observeDraftsForOwner = { owner -> database.chatDraftDao().observeForOwner(owner) },
            deleteDraftForChat = { owner, chatId ->
                database.chatDraftDao().deleteForChat(owner, chatId)
            },
            listLockedChatIds = { database.chatLockDao().listLockedChatIds().toSet() },
            searchChatIdsByMessageContent = { escaped ->
                database.messageDao().searchChatIdsByMessageContent(escaped)
            },
            listSecretChatIds = { database.chatDao().listSecretChatIds().toSet() },
            trustChangedRemoteIds = { owner, remoteIds ->
                remoteIds.filter { remoteId ->
                    try {
                        database.identityTrustDao().getAllTrustForUser(owner, remoteId)
                            .any { it.trustState == PersistentSignalProtocolStore.TRUST_CHANGED }
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                }.toSet()
            },
            isFlagEnabled = { flag -> RuntimeFlags.isEnabled(application, flag) },
        )
    }
}
