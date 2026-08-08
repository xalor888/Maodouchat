package com.maodouchat.call

import com.maodouchat.webrtc.CallType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object IncomingCallCoordinator {

    data class PendingIncomingCall(
        val contactId: String,
        val contactName: String,
        val callType: CallType,
        val offerSdp: String,
        val callId: String = "",
        val groupId: String = "",
        val groupMemberIds: List<String> = emptyList(),
        val receivedAtMillis: Long = System.currentTimeMillis(),
        /** 8.56：系统 Telecom 来电 UI 已点「接听」——进入来电路由后直接自动接听。 */
        val autoAnswer: Boolean = false,
    )

    const val STALE_MS = 120_000L

    private val _pending = MutableStateFlow<PendingIncomingCall?>(null)
    val pending: StateFlow<PendingIncomingCall?> = _pending.asStateFlow()

    // "已消费" 通知通道：consumePending/clear 被调用时触发，外部可监听以执行未接来电超时检测
    private val _consumedEvents = MutableStateFlow<Long>(0L)
    val consumedEvents: StateFlow<Long> = _consumedEvents.asStateFlow()

    fun setPending(call: PendingIncomingCall) {
        _pending.value = call
    }

    fun consumePending(): PendingIncomingCall? {
        val current = _pending.value ?: return null
        _pending.value = null
        _consumedEvents.value = System.currentTimeMillis()
        return if (System.currentTimeMillis() - current.receivedAtMillis > STALE_MS) null else current
    }

    /** 非消费读 — 用于 LaunchedEffect 重建 answer 参数（rotation 后 consumePending 已消耗） */
    fun peekPending(): PendingIncomingCall? {
        val current = _pending.value ?: return null
        if (System.currentTimeMillis() - current.receivedAtMillis > STALE_MS) {
            _pending.value = null
            return null
        }
        return current
    }

    fun clear() {
        if (_pending.value != null) {
            _pending.value = null
            _consumedEvents.value = System.currentTimeMillis()
        }
    }
}
