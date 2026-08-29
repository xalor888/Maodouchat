package com.maodouchat.messaging.v2

import android.util.Log
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-scoped lifecycle owner for durable v2 transport convergence. */
class MessagingV2Runtime(
    private val app: MaodouchatApp,
    private val scope: CoroutineScope,
) {
    private val tokenManager = TokenManager.getInstance(app)
    private val messageStore = LocalMessageStore(app.database.messageDao(), app.database)
    val outbox = MessagingV2Outbox(
        database = app.database,
        dao = app.database.messagingV2Dao(),
        ownerUserId = { tokenManager.getUserId().orEmpty() },
        deviceId = app.signalProtocol::getDeviceId,
        wakeTransport = ::wakeTransport,
    )
    private val notifier by lazy {
        MessagingV2ArrivalNotifier(
            app = app,
            ownerUserId = { tokenManager.getUserId().orEmpty() },
        )
    }
    private val timelineProjector by lazy {
        MessagingV2TimelineProjector(
            app = app,
            messageStore = messageStore,
            ownerUserId = { tokenManager.getUserId().orEmpty() },
            notifier = notifier,
            sendDeliveryReceipt = outbox::enqueueDeliveryReceipt,
            onAuthoritativeMutation = app.messagingV2MutationEvents::publish,
            onSenderKeyRequest = { conversationId, epoch, requesterUserId ->
                app.senderKeyRetryManager.enqueue(
                    conversationId,
                    epoch,
                    "v2_peer_request:${requesterUserId.take(40)}",
                    delayMs = 0L,
                )
                runCatching {
                    app.senderKeyRetryManager.ensureCoverageNow(conversationId, epoch).getOrThrow()
                }
            },
        )
    }
    private val inboxSynchronizer by lazy {
        MessagingV2InboxSynchronizer(
            dao = app.database.messagingV2Dao(),
            processor = SignalMessagingV2EnvelopeProcessor(
                signalProtocol = app.signalProtocol,
                domainSink = timelineProjector::project,
                groupRevisionProvider = { chatId ->
                    app.database.chatDao().getChatById(chatId)?.memberRevision
                },
                onSenderKeyMissing = { envelope, epoch ->
                    outbox.enqueueSenderKeyRequest(
                        conversationId = envelope.conversationId,
                        requestedSenderUserId = envelope.senderUserId,
                        groupRevision = epoch,
                        failedMessageId = envelope.messageId,
                    )
                },
            ),
        )
    }
    private val outboxCoordinator by lazy {
        MessagingV2OutboxCoordinator(
            dao = app.database.messagingV2Dao(),
            preparer = SignalMessagingV2EnvelopePreparer(
                signalProtocol = app.signalProtocol,
                snapshotProvider = ApiMessagingV2ConversationSnapshotProvider {
                    tokenManager.getUserId().orEmpty()
                },
                ensureGroupReady = { groupId, epoch ->
                    app.senderKeyRetryManager.ensureCoverageNow(groupId, epoch).getOrThrow()
                },
            ),
            onCompleted = { message ->
                messageStore.updateMessageStatus(message.messageId, MessageStatus.SENT)
            },
        )
    }
    private var started = false
    private var eventJob: Job? = null
    private var pollJob: Job? = null
    private val syncMutex = Mutex()

    @Synchronized
    internal fun start() {
        if (started) return
        started = true
        eventJob = scope.launch {
            WebSocketClient.events.collect { event ->
                if (event is WebSocketEvent.InboxAvailableV2) syncOnce()
            }
        }
        pollJob = scope.launch {
            while (isActive) {
                syncOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    internal suspend fun syncNow() = syncOnce()

    /**
     * Pauses both receive and send convergence while destructive conversation state is removed.
     * This prevents an already-claimed envelope or outbox row from projecting after cleanup.
     */
    internal suspend fun clearConversationState(
        ownerUserId: String,
        sessionGeneration: Long,
        conversationId: String,
        serverParticipantStateDeleted: Boolean,
    ) = syncMutex.withLock {
        if (
            tokenManager.getUserId() != ownerUserId ||
            MaodouchatApp.currentSessionGeneration() != sessionGeneration ||
            com.maodouchat.security.SecureSessionManager.isPurgeInProgress()
        ) {
            throw CancellationException("messaging_v2_cleanup_session_changed")
        }
        app.database.messagingV2Dao().clearConversationState(
            ownerUserId = ownerUserId,
            conversationId = conversationId,
            serverParticipantStateDeleted = serverParticipantStateDeleted,
            now = System.currentTimeMillis(),
        )
        if (!serverParticipantStateDeleted) wakeTransport()
    }

    private suspend fun syncOnce() = syncMutex.withLock {
        val token = tokenManager.getToken()?.takeIf(String::isNotBlank) ?: return@withLock
        val owner = tokenManager.getUserId()?.takeIf(String::isNotBlank) ?: return@withLock
        if (!app.signalProtocol.isLocalCryptoReadyFor(owner)) return@withLock
        val deviceId = app.signalProtocol.getDeviceId()
        runCatching {
            inboxSynchronizer.sync(token, owner, deviceId)
            outboxCoordinator.flush(token, owner)
        }.onFailure { error ->
            Log.w(TAG, "v2 transport convergence deferred: ${error.message}")
        }
    }

    private fun wakeTransport() {
        scope.launch { syncOnce() }
    }

    private companion object {
        const val TAG = "MessagingV2Runtime"
        const val POLL_INTERVAL_MS = 30_000L
    }
}
