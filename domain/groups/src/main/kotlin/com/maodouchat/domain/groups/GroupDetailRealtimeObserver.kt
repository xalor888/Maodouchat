package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupDetailUiState
import com.maodouchat.core.model.GroupMessagingCoordinator
import com.maodouchat.core.model.resolveUserVisibility
import com.maodouchat.core.realtime.RealtimeDomainEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 群详情实时事件观察（Presence 可见性 / GroupRevision 失效与重载），由 app 侧纯搬移；
// 实时事件流、会话门禁与可见性写库走端口（app 侧接线），时钟走写库默认值。
class GroupDetailRealtimeObserver(
    private val scope: CoroutineScope,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val chatId: () -> String,
    private val ownerUserId: () -> String,
    private val groupMessagingCoordinator: GroupMessagingCoordinator,
    private val onRevisionReload: () -> Unit,
    private val eventsFlow: Flow<RealtimeDomainEvent>?,
    private val sessionActive: (String) -> Boolean,
    private val applyRealtimeVisibility: suspend (
        userId: String,
        isOnline: Boolean,
        onlineRevoked: Boolean,
        statusRevoked: Boolean,
    ) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun start() {
        val revisionOwnerUserId = ownerUserId()
        // ui 不直接依赖 app 单例：实时事件流由 app 侧经 AppRuntime 取（非本应用实例 → 不订阅，
        // 与原 `as MaodouchatApp` 的生产语义一致；测试替身下静默跳过）。
        val flow = eventsFlow ?: return
        scope.launch {
            flow.collect { event ->
                if (
                    revisionOwnerUserId.isBlank() ||
                    !sessionActive(revisionOwnerUserId)
                ) {
                    return@collect
                }
                if (event is RealtimeDomainEvent.Presence) {
                    if (event.onlineRevoked || event.statusRevoked) {
                        // 函数类型值不接受具名实参：按声明顺序位置传参。
                        applyRealtimeVisibility(
                            event.userId,
                            event.isOnline,
                            event.onlineRevoked,
                            event.statusRevoked,
                        )
                    }
                    updateState { state ->
                        state.copy(
                            members = state.members.map { member ->
                                if (member.userId != event.userId) {
                                    member
                                } else {
                                    val visibility = resolveUserVisibility(
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
                                    val visibility = resolveUserVisibility(
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
                        withContext(ioDispatcher) {
                            if (!sessionActive(revisionOwnerUserId)) {
                                return@withContext
                            }
                            groupMessagingCoordinator.invalidateSenderKey(
                                chatId(),
                                revisionOwnerUserId,
                                event.memberRevision,
                            )
                        }
                    }
                    if (!sessionActive(revisionOwnerUserId)) {
                        return@collect
                    }
                    onRevisionReload()
                }
            }
        }
    }
}
