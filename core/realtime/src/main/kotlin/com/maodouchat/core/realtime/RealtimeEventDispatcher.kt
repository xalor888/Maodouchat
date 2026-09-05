package com.maodouchat.core.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterIsInstance

/**
 * A05: 集中式实时事件分发器 (RealtimeEventDispatcher)。
 * 1. 唯一管理领域事件分发；
 * 2. 自动对 Wake 事件做 debounce/coalescing 合并（避免高频 wake 引起多次并发 sync）；
 * 3. 页面不直接访问底层 WebSocket 传输层，按需订阅领域 Flow。
 */
interface RealtimeEventDispatcher {
    val allEvents: SharedFlow<RealtimeDomainEvent>
    val wakeEvents: Flow<RealtimeDomainEvent.Wake>
    val presenceEvents: Flow<RealtimeDomainEvent.Presence>
    val typingEvents: Flow<RealtimeDomainEvent.Typing>
    val callSignalingEvents: Flow<RealtimeDomainEvent.CallSignaling>
    val groupRevisionEvents: Flow<RealtimeDomainEvent.GroupRevision>
    val groupPlayEvents: Flow<RealtimeDomainEvent.GroupPlay>
    val groupInviteEvents: Flow<RealtimeDomainEvent.GroupInvite>
    val adminNoticeEvents: Flow<RealtimeDomainEvent.AdminNotice>
    val socialEvents: Flow<RealtimeDomainEvent.SocialUpdate>
    val errorEvents: Flow<RealtimeDomainEvent.RealtimeError>
    val connectionState: StateFlow<RealtimeConnectionState>

    fun dispatch(event: RealtimeDomainEvent)
    fun updateConnectionState(state: RealtimeConnectionState, message: String? = null)
}

/**
 * 默认领域实时事件分发器实现。
 * 支持 Wake 事件合流防抖 (coalescing) 与多端口类型安全分派。
 */
@OptIn(FlowPreview::class)
class DefaultRealtimeEventDispatcher(
    wakeCoalesceMs: Long = 150L,
) : RealtimeEventDispatcher {

    private val _allEvents = MutableSharedFlow<RealtimeDomainEvent>(extraBufferCapacity = 64)
    override val allEvents: SharedFlow<RealtimeDomainEvent> = _allEvents.asSharedFlow()

    private val rawWakeFlow = MutableSharedFlow<RealtimeDomainEvent.Wake>(extraBufferCapacity = 16)
    override val wakeEvents: Flow<RealtimeDomainEvent.Wake> = rawWakeFlow.debounce(wakeCoalesceMs)

    override val presenceEvents: Flow<RealtimeDomainEvent.Presence> =
        _allEvents.filterIsInstance()

    override val typingEvents: Flow<RealtimeDomainEvent.Typing> =
        _allEvents.filterIsInstance()

    override val callSignalingEvents: Flow<RealtimeDomainEvent.CallSignaling> =
        _allEvents.filterIsInstance()

    override val groupRevisionEvents: Flow<RealtimeDomainEvent.GroupRevision> =
        _allEvents.filterIsInstance()

    override val groupPlayEvents: Flow<RealtimeDomainEvent.GroupPlay> =
        _allEvents.filterIsInstance()

    override val groupInviteEvents: Flow<RealtimeDomainEvent.GroupInvite> =
        _allEvents.filterIsInstance()

    override val adminNoticeEvents: Flow<RealtimeDomainEvent.AdminNotice> =
        _allEvents.filterIsInstance()

    override val socialEvents: Flow<RealtimeDomainEvent.SocialUpdate> =
        _allEvents.filterIsInstance()

    override val errorEvents: Flow<RealtimeDomainEvent.RealtimeError> =
        _allEvents.filterIsInstance()

    private val _connectionState = MutableStateFlow(RealtimeConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<RealtimeConnectionState> = _connectionState.asStateFlow()

    override fun dispatch(event: RealtimeDomainEvent) {
        if (event is RealtimeDomainEvent.Wake) {
            rawWakeFlow.tryEmit(event)
        }
        _allEvents.tryEmit(event)
    }

    override fun updateConnectionState(state: RealtimeConnectionState, message: String?) {
        _connectionState.value = state
        dispatch(RealtimeDomainEvent.ConnectionStateChanged(state, message))
    }
}
