package com.maodouchat.ui.screen.chatdetail

import android.app.Application
import com.maodouchat.group.GroupDetailUiState
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.messaging.v2.GroupMessagingCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * G368：群详情的实时事件观察（Presence 可见性 / GroupRevision 失效与重载）从
 * `GroupDetailViewModel` 抽出（纯搬移不改判断）——在 VM 的 init 处启动。
 */
internal class GroupDetailRealtimeObserver(
    private val scope: CoroutineScope,
    private val application: Application,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val chatId: () -> String,
    private val ownerUserId: () -> String,
    private val groupMessagingCoordinator: GroupMessagingCoordinator,
    private val onRevisionReload: () -> Unit,
) {
    fun start() {
        val revisionOwnerUserId = ownerUserId()
        // ui 不直接依赖 app 单例：实时事件分发器经 AppRuntime 取（非本应用实例 → 不订阅，
        // 与原 `as MaodouchatApp` 的生产语义一致；测试替身下静默跳过）。
        val eventsFlow = com.maodouchat.session.AppRuntime
            .realtimeDispatcherOrNull(application)
            ?.allEvents ?: return
        scope.launch {
            eventsFlow.collect { event ->
                if (
                    revisionOwnerUserId.isBlank() ||
                    !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = revisionOwnerUserId,
                    )
                ) {
                    return@collect
                }
                if (event is RealtimeDomainEvent.Presence) {
                    if (event.onlineRevoked || event.statusRevoked) {
                        com.maodouchat.data.repository.AppRepositories.users.applyRealtimeVisibility(
                            userId = event.userId,
                            isOnline = event.isOnline,
                            onlineRevoked = event.onlineRevoked,
                            statusRevoked = event.statusRevoked,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    updateState { state ->
                        state.copy(
                            members = state.members.map { member ->
                                if (member.userId != event.userId) {
                                    member
                                } else {
                                    val visibility = com.maodouchat.network.resolveUserVisibility(
                                        currentIsOnline = member.isOnline,
                                        currentStatus = "",
                                        currentLastSeen = 0L,
                                        eventIsOnline = event.isOnline,
                                        eventLastSeen = event.lastSeen,
                                        onlineRevoked = event.onlineRevoked,
                                        statusRevoked = event.statusRevoked
                                    )
                                    member.copy(
                                        isOnline = visibility.isOnline
                                    )
                                }
                            },
                            candidates = state.candidates.map { candidate ->
                                if (candidate.id != event.userId) {
                                    candidate
                                } else {
                                    val visibility = com.maodouchat.network.resolveUserVisibility(
                                        currentIsOnline = candidate.isOnline,
                                        currentStatus = candidate.status,
                                        currentLastSeen = candidate.lastSeen,
                                        eventIsOnline = event.isOnline,
                                        eventLastSeen = event.lastSeen,
                                        onlineRevoked = event.onlineRevoked,
                                        statusRevoked = event.statusRevoked
                                    )
                                    candidate.copy(
                                        isOnline = visibility.isOnline,
                                        status = visibility.status,
                                        lastSeen = visibility.lastSeen
                                    )
                                }
                            }
                        )
                    }
                }
                if (event is RealtimeDomainEvent.GroupRevision && event.chatId == chatId()) {
                    if (event.memberRevision > currentState().memberRevision) {
                        withContext(Dispatchers.IO) {
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                expectedUserId = revisionOwnerUserId,
                            )
                            ) {
                                return@withContext
                            }
                            groupMessagingCoordinator.invalidateSenderKey(
                                chatId(),
                                revisionOwnerUserId,
                                event.memberRevision,
                            )
                        }
                    }
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = revisionOwnerUserId,
                    )
                    ) {
                        return@collect
                    }
                    onRevisionReload()
                }
            }
        }
    }
}
