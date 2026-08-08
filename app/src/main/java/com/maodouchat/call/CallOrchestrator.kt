package com.maodouchat.call

import com.maodouchat.webrtc.CallType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 全局群通话请求通道：ChatDetailViewModel 把"发起群通话"的请求 emit 进来，
 * 路由层 NavGraph 订阅后实际启动 CallScreen / CallViewModel。
 * 避免在 ViewModel 里直接持有 NavController。
 *
 * SharedFlow 缓冲 1 条：logout 时 [invalidateSession] 抬高 generation，
 * 路由层只处理当前 generation，避免换号后回放上一账号的群通话/私聊跳转。
 */
object CallOrchestrator {
    @Volatile
    private var sessionGeneration: Long = 0L

    private val _groupCallRequests = MutableSharedFlow<GroupCallRequest>(extraBufferCapacity = 1)
    val groupCallRequests: SharedFlow<GroupCallRequest> = _groupCallRequests.asSharedFlow()

    // 来自扫一扫的"和某人创建私聊"请求：直接由 NavGraph 监听并跳到 ChatDetail
    private val _directChatRequests = MutableSharedFlow<DirectChatRequest>(extraBufferCapacity = 1)
    val directChatRequests: SharedFlow<DirectChatRequest> = _directChatRequests.asSharedFlow()

    fun currentSessionGeneration(): Long = sessionGeneration

    fun invalidateSession() {
        sessionGeneration += 1L
    }

    fun requestGroupCall(chatId: String, memberIds: List<String>, callType: CallType) {
        _groupCallRequests.tryEmit(GroupCallRequest(chatId, memberIds, callType, sessionGeneration))
    }

    fun requestDirectChat(userId: String, userName: String) {
        _directChatRequests.tryEmit(DirectChatRequest(userId, userName, sessionGeneration))
    }

    data class GroupCallRequest(
        val chatId: String,
        val memberIds: List<String>,
        val callType: CallType,
        val sessionGeneration: Long = currentSessionGeneration(),
    )
    data class DirectChatRequest(
        val userId: String,
        val userName: String,
        val sessionGeneration: Long = currentSessionGeneration(),
    )
}