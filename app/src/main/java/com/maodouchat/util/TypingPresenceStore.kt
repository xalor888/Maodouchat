package com.maodouchat.util

import com.maodouchat.network.REMOTE_TYPING_TIMEOUT_MS
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/**
 * 1.103：全局「正在输入」presence（供会话列表显示）。
 * 订阅 WS 全局事件流（[WebSocketClient.events]），按 chatId 维护对端输入状态，
 * 3s 无更新自动过期；登录用户变化时自动清空，避免跨账号串扰。
 */
object TypingPresenceStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val expiryJobs = mutableMapOf<String, Job>()
    private var trackedUserId: String? = null

    private val _typingByChat = MutableStateFlow<Map<String, String>>(emptyMap())
    /** chatId -> 正在输入的对端 userId。 */
    val typingByChat: StateFlow<Map<String, String>> = _typingByChat.asStateFlow()

    fun isTyping(chatId: String): String? = _typingByChat.value[chatId]

    /** 应用启动时调用一次：订阅领域 typing 事件流。 */
    fun start(dispatcher: com.maodouchat.core.realtime.RealtimeEventDispatcher) {
        scope.launch {
            dispatcher.typingEvents.collect { event ->
                handleTyping(event.chatId, event.userId, event.isTyping)
            }
        }
    }

    /** 兼容历史传输层事件调用。 */
    fun start(events: SharedFlow<WebSocketEvent>) {
        scope.launch {
            events.filterIsInstance<WebSocketEvent.UserTyping>().collect { event ->
                handleTyping(event.chatId, event.userId, event.isTyping)
            }
        }
    }

    /** 登出/会话失效时清空。 */
    fun clear() {
        synchronized(lock) {
            trackedUserId = null
            expiryJobs.values.forEach(Job::cancel)
            expiryJobs.clear()
            _typingByChat.value = emptyMap()
        }
    }

    private fun handleTyping(chatId: String, userId: String, isTyping: Boolean) {
        val currentUserId = TokenManager.getInstanceOrNull()?.getUserId().orEmpty()
        if (currentUserId.isBlank() || userId.isBlank() || userId == currentUserId || userId == "me") return
        synchronized(lock) {
            if (trackedUserId != currentUserId) {
                trackedUserId = currentUserId
                expiryJobs.values.forEach(Job::cancel)
                expiryJobs.clear()
                _typingByChat.value = emptyMap()
            }
            if (isTyping) renew(chatId, userId)
            else stop(chatId)
        }
    }

    private fun renew(chatId: String, userId: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(REMOTE_TYPING_TIMEOUT_MS)
            expire(chatId)
        }
        expiryJobs.remove(chatId)?.cancel()
        expiryJobs[chatId] = job
        _typingByChat.value = _typingByChat.value + (chatId to userId)
        job.start()
    }

    private fun stop(chatId: String) {
        if (!_typingByChat.value.containsKey(chatId)) return
        expiryJobs.remove(chatId)?.cancel()
        _typingByChat.value = _typingByChat.value - chatId
    }

    private fun expire(chatId: String) {
        synchronized(lock) {
            expiryJobs.remove(chatId)
            _typingByChat.value = _typingByChat.value - chatId
        }
    }
}
