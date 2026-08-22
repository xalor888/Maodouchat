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
        // 9.163：读-清必须原子——Telecom「接听」回调与来电路由组合可并发消费，
        // 此前两步读写在两个消费方同时到达时会把同一来电交给两处（重复接听/挂断处理）
        var current: PendingIncomingCall? = null
        while (true) {
            val snapshot = _pending.value
            if (snapshot == null) break
            if (_pending.compareAndSet(snapshot, null)) {
                current = snapshot
                break
            }
        }
        if (current == null) return null
        _consumedEvents.value = System.currentTimeMillis()
        return if (System.currentTimeMillis() - current.receivedAtMillis > STALE_MS) null else current
    }

    /**
     * Telecom 系统接听后把同一 pending **替换**成 autoAnswer=true 的新对象。
     * IncomingCallRoute 用 peek 快照 + LaunchedEffect(incomingCall)，必须换实例才能重跑自动接听。
     */
    fun markAutoAnswer(callId: String): Boolean {
        while (true) {
            val current = _pending.value ?: return false
            if (System.currentTimeMillis() - current.receivedAtMillis > STALE_MS) {
                clear()
                return false
            }
            if (callId.isNotBlank() && current.callId.isNotBlank() && current.callId != callId) {
                return false
            }
            if (current.autoAnswer) return true
            val updated = current.copy(autoAnswer = true)
            if (_pending.compareAndSet(current, updated)) return true
        }
    }

    /** 非消费读 — 用于 LaunchedEffect 重建 answer 参数（rotation 后 consumePending 已消耗） */
    fun peekPending(): PendingIncomingCall? {
        val current = _pending.value ?: return null
        if (System.currentTimeMillis() - current.receivedAtMillis > STALE_MS) {
            clear()
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
