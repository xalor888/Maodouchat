package com.maodouchat.domain.messaging

import com.maodouchat.core.realtime.RealtimeEventDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 全局「正在输入」presence（供会话列表显示）：订阅领域 typing 事件流，
// 按 chatId 维护对端输入状态，3s 无更新自动过期；登录用户变化时自动清空，避免跨账号串扰。
object TypingPresenceStore {
    private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val expiryJobs = mutableMapOf<String, Job>()
    private var trackedUserId: String? = null

    private val _typingByChat = MutableStateFlow<Map<String, String>>(emptyMap())
    /** chatId -> 正在输入的对端 userId。 */
    val typingByChat: StateFlow<Map<String, String>> = _typingByChat.asStateFlow()

    fun isTyping(chatId: String): String? = _typingByChat.value[chatId]

    /** 应用启动时调用一次：订阅领域 typing 事件流。 */
    fun start(
        dispatcher: RealtimeEventDispatcher,
        currentUserId: () -> String,
        scope: CoroutineScope = defaultScope,
        timeoutMs: Long = REMOTE_TYPING_TIMEOUT_MS,
    ) {
        scope.launch {
            dispatcher.typingEvents.collect { event ->
                handleTyping(event.chatId, event.userId, event.isTyping, currentUserId, scope, timeoutMs)
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

    private fun handleTyping(
        chatId: String,
        userId: String,
        isTyping: Boolean,
        currentUserId: () -> String,
        scope: CoroutineScope,
        timeoutMs: Long,
    ) {
        val me = currentUserId()
        if (me.isBlank() || userId.isBlank() || userId == me || userId == "me") return
        synchronized(lock) {
            if (trackedUserId != me) {
                trackedUserId = me
                expiryJobs.values.forEach(Job::cancel)
                expiryJobs.clear()
                _typingByChat.value = emptyMap()
            }
            if (isTyping) renew(chatId, userId, scope, timeoutMs)
            else stop(chatId)
        }
    }

    private fun renew(chatId: String, userId: String, scope: CoroutineScope, timeoutMs: Long) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(timeoutMs)
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
