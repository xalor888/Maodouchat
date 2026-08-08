package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.network.REMOTE_TYPING_TIMEOUT_MS
import com.maodouchat.network.shouldApplyTypingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Maintains an independent lease per remote typing user and publishes the most recent one. */
internal class RemoteTypingCoordinator(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = REMOTE_TYPING_TIMEOUT_MS,
    private val onTypingUserChanged: (String?) -> Unit
) {
    private val lock = Any()
    private val activeGenerations = linkedMapOf<String, Long>()
    private val expiryJobs = mutableMapOf<String, Job>()
    private var generation = 0L

    fun onEvent(activeChatId: String, eventChatId: String, userId: String, isTyping: Boolean) {
        if (!shouldApplyTypingEvent(activeChatId, eventChatId) || userId.isBlank()) return
        if (isTyping) renew(userId) else stop(userId)
    }

    private fun renew(userId: String) {
        val currentGeneration: Long
        val job: Job
        synchronized(lock) {
            currentGeneration = ++generation
            activeGenerations.remove(userId)
            activeGenerations[userId] = currentGeneration
            expiryJobs.remove(userId)?.cancel()
            job = scope.launch(start = CoroutineStart.LAZY) {
                delay(timeoutMs)
                expire(userId, currentGeneration)
            }
            expiryJobs[userId] = job
        }
        onTypingUserChanged(userId)
        job.start()
    }

    private fun stop(userId: String) {
        val nextUser: String?
        synchronized(lock) {
            if (activeGenerations.remove(userId) == null) return
            expiryJobs.remove(userId)?.cancel()
            nextUser = activeGenerations.keys.lastOrNull()
        }
        onTypingUserChanged(nextUser)
    }

    private fun expire(userId: String, expectedGeneration: Long) {
        val nextUser: String?
        synchronized(lock) {
            if (activeGenerations[userId] != expectedGeneration) return
            activeGenerations.remove(userId)
            expiryJobs.remove(userId)
            nextUser = activeGenerations.keys.lastOrNull()
        }
        onTypingUserChanged(nextUser)
    }

    fun clear() {
        val hadActiveUsers: Boolean
        synchronized(lock) {
            hadActiveUsers = activeGenerations.isNotEmpty()
            activeGenerations.clear()
            expiryJobs.values.forEach(Job::cancel)
            expiryJobs.clear()
        }
        if (hadActiveUsers) onTypingUserChanged(null)
    }
}
