package com.maodouchat.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Only ephemeral/control events use this bus. Durable chat data converges through v2 Inbox and
 * Room, while INBOX_AVAILABLE_V2 is merely a wake-up hint that periodic polling can recover.
 *
 * 有界队列 + 单消费者桥接：生产者（WebSocket 回调线程）经 [post] 永不阻塞；消费者协程把
 * 事件顺序 emit 到底层 SharedFlow（SUSPEND 背压）——collector 慢时消费协程挂起。
 * 队列有界（DROP_OLDEST）：极端积压时丢弃最旧事件而非无限堆积直至 OOM——
 * Durable inbox wake-ups can be dropped safely; typing/presence are intentionally best effort.
 */
internal class NonReplayingEventBus<T>(capacity: Int, scope: CoroutineScope) {
    private val events = MutableSharedFlow<T>(
        replay = 0,
        extraBufferCapacity = capacity.coerceAtLeast(1),
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    private val queue = Channel<T>(capacity.coerceAtLeast(1), BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            for (event in queue) events.emit(event)
        }
    }

    val flow: SharedFlow<T> = events.asSharedFlow()

    /** 回调线程安全：有界队列 trySend 快速返回，队列满时丢弃最旧事件（不阻塞回调线程）。 */
    fun post(event: T) {
        queue.trySend(event)
    }
}

/** Rejects callbacks from sockets superseded by token/account changes or explicit disconnect. */
internal class WebSocketSessionGate {
    private val generation = AtomicLong(0L)

    fun nextSession(): Long = generation.incrementAndGet()

    fun invalidate() {
        generation.incrementAndGet()
    }

    /** 8.33：当前会话代号（重连 job 捕获其失败时的会话，connect 时校验仍为当前）。 */
    fun current(): Long = generation.get()

    fun isCurrent(session: Long): Boolean = generation.get() == session
}

internal fun shouldApplyTypingEvent(activeChatId: String, eventChatId: String): Boolean =
    activeChatId.isNotBlank() && activeChatId == eventChatId

internal enum class TypingSignalAction { START, STOP, NONE }

internal fun resolveTypingSignalAction(isAnnounced: Boolean, hasInput: Boolean): TypingSignalAction =
    when {
        hasInput && !isAnnounced -> TypingSignalAction.START
        !hasInput && isAnnounced -> TypingSignalAction.STOP
        else -> TypingSignalAction.NONE
    }

internal const val REMOTE_TYPING_TIMEOUT_MS = 3_000L
