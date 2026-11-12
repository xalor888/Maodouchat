package com.maodouchat.domain.contacts

import com.maodouchat.core.realtime.RealtimeDomainEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 默认实时同步协调器实现。
 *
 * 核心设计：
 * 1. 消除无序竞争：内部使用 Mutex 对写库与好友缓存操作串行化。
 * 2. 账号安全隔离：校验会话门禁，丢弃过期或串号事件。
 * 3. 单一真实来源：状态变更直接下沉至 Room，由 Room Flow 驱动 UI，消除 ViewModel 双重状态竞争。
 *
 * 写库、好友缓存、会话门禁与时钟均走构造端口，domain 不依赖 Android / Room。
 */
class DefaultContactsRealtimeSyncCoordinator(
    private val applyRealtimeVisibility: suspend (userId: String, isOnline: Boolean, onlineRevoked: Boolean, statusRevoked: Boolean, updatedAt: Long) -> Unit,
    private val addFriendToCache: (friendId: String, ownerUserId: String) -> Unit,
    private val sessionMayContinue: (expectedUserId: String, liveToken: String?, liveUserId: String?) -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val onNotificationCenterItem: ((id: String, title: String, subtitle: String, message: String?) -> Unit)? = null
) : ContactsRealtimeSyncCoordinator {

    private val syncMutex = Mutex()
    private val _syncEvents = MutableSharedFlow<ContactSyncEvent>(extraBufferCapacity = 64)
    override val syncEvents: Flow<ContactSyncEvent> = _syncEvents.asSharedFlow()

    override suspend fun handleEvent(event: RealtimeDomainEvent, expectedOwnerUserId: String, currentToken: String) {
        if (expectedOwnerUserId.isBlank() || currentToken.isBlank()) return

        // 函数类型值调用不允许具名实参，改位置实参。
        if (!sessionMayContinue(expectedOwnerUserId, currentToken, expectedOwnerUserId)) {
            return
        }

        syncMutex.withLock {
            try {
                when (event) {
                    is RealtimeDomainEvent.Presence -> {
                        // 写入 Room，单向触发 Room 数据库 Flow 刷新
                        applyRealtimeVisibility(
                            event.userId,
                            event.isOnline,
                            event.onlineRevoked,
                            event.statusRevoked,
                            nowMs()
                        )
                    }

                    is RealtimeDomainEvent.SocialUpdate -> {
                        if (event.kind == "friend_request") {
                            _syncEvents.emit(ContactSyncEvent.FriendRequestsNeedsRefresh)

                            if (event.action == "ACCEPTED") {
                                val friendId = if (event.fromUserId == expectedOwnerUserId) {
                                    event.toUserId
                                } else {
                                    event.fromUserId
                                }
                                if (!friendId.isNullOrBlank()) {
                                    addFriendToCache(friendId, expectedOwnerUserId)
                                    _syncEvents.emit(ContactSyncEvent.FriendAccepted(friendId, event.fromUserName))
                                }
                            } else if (event.action == "CREATED" && event.toUserId == expectedOwnerUserId) {
                                onNotificationCenterItem?.invoke(
                                    "friend_${event.targetId}",
                                    "好友申请",
                                    event.fromUserName.orEmpty(),
                                    event.message
                                )
                            }
                        }
                    }

                    is RealtimeDomainEvent.GroupInvite -> {
                        _syncEvents.emit(ContactSyncEvent.GroupInvitesNeedsRefresh)
                        if (event.action == "CREATED" && event.inviteeId == expectedOwnerUserId) {
                            onNotificationCenterItem?.invoke(
                                "group_invite_${event.inviteId}",
                                "群邀请",
                                event.inviterName,
                                event.chatName
                            )
                        }
                    }

                    else -> {
                        // 忽略非联系人/关系事件
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // 异常防御，不崩溃
            }
        }
    }

    override fun startObserving(
        scope: CoroutineScope,
        eventsFlow: Flow<RealtimeDomainEvent>,
        sessionProvider: () -> Pair<String?, String?>
    ): Job {
        return scope.launch(Dispatchers.Default) {
            eventsFlow.collect { event ->
                val (userId, token) = sessionProvider()
                if (!userId.isNullOrBlank() && !token.isNullOrBlank()) {
                    handleEvent(event, userId, token)
                }
            }
        }
    }
}
