package com.maodouchat.core.realtime

import com.maodouchat.core.model.WebSocketErrorKind
import com.maodouchat.core.model.WebSocketEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class WebSocketEventBridgeTest {

    private class RecordingDispatcher : RealtimeEventDispatcher {
        val dispatched = mutableListOf<RealtimeDomainEvent>()
        val states = mutableListOf<Pair<RealtimeConnectionState, String?>>()
        override val allEvents: SharedFlow<RealtimeDomainEvent> = MutableSharedFlow()
        override val wakeEvents: Flow<RealtimeDomainEvent.Wake> = emptyFlow()
        override val presenceEvents: Flow<RealtimeDomainEvent.Presence> = emptyFlow()
        override val typingEvents: Flow<RealtimeDomainEvent.Typing> = emptyFlow()
        override val callSignalingEvents: Flow<RealtimeDomainEvent.CallSignaling> = emptyFlow()
        override val groupRevisionEvents: Flow<RealtimeDomainEvent.GroupRevision> = emptyFlow()
        override val groupPlayEvents: Flow<RealtimeDomainEvent.GroupPlay> = emptyFlow()
        override val groupInviteEvents: Flow<RealtimeDomainEvent.GroupInvite> = emptyFlow()
        override val adminNoticeEvents: Flow<RealtimeDomainEvent.AdminNotice> = emptyFlow()
        override val socialEvents: Flow<RealtimeDomainEvent.SocialUpdate> = emptyFlow()
        override val errorEvents: Flow<RealtimeDomainEvent.RealtimeError> = emptyFlow()
        override val connectionState: StateFlow<RealtimeConnectionState> =
            MutableStateFlow(RealtimeConnectionState.DISCONNECTED)

        override fun dispatch(event: RealtimeDomainEvent) {
            dispatched.add(event)
        }

        override fun updateConnectionState(state: RealtimeConnectionState, message: String?) {
            states.add(state to message)
        }
    }

    private fun harness(): Triple<RecordingDispatcher, MutableSharedFlow<WebSocketEvent>, WebSocketEventBridge> {
        val dispatcher = RecordingDispatcher()
        val events = MutableSharedFlow<WebSocketEvent>(extraBufferCapacity = 64)
        return Triple(dispatcher, events, WebSocketEventBridge(dispatcher))
    }

    @Test
    fun inboxAvailableDispatchesWake() = runTest {
        val (dispatcher, events, bridge) = harness()
        bridge.start(events, backgroundScope)
        // 先让收集器订阅：replay=0 的 SharedFlow 不会把订阅前的事件补给新订阅者。
        runCurrent()
        events.emit(WebSocketEvent.InboxAvailableV2)
        runCurrent()
        assertEquals(1, dispatcher.dispatched.size)
        assertIs<RealtimeDomainEvent.Wake>(dispatcher.dispatched.single())
    }

    @Test
    fun userTypingDispatchesTyping() = runTest {
        val (dispatcher, events, bridge) = harness()
        bridge.start(events, backgroundScope)
        // 先让收集器订阅：replay=0 的 SharedFlow 不会把订阅前的事件补给新订阅者。
        runCurrent()
        events.emit(WebSocketEvent.UserTyping("user-a", "chat-1", true))
        runCurrent()
        assertEquals(
            RealtimeDomainEvent.Typing("user-a", "chat-1", true),
            dispatcher.dispatched.single()
        )
    }

    @Test
    fun userOnlineDispatchesPresence() = runTest {
        val (dispatcher, events, bridge) = harness()
        bridge.start(events, backgroundScope)
        // 先让收集器订阅：replay=0 的 SharedFlow 不会把订阅前的事件补给新订阅者。
        runCurrent()
        events.emit(WebSocketEvent.UserOnline("user-a", true, lastSeen = 7L))
        runCurrent()
        assertEquals(
            RealtimeDomainEvent.Presence("user-a", true, 7L, false, false),
            dispatcher.dispatched.single()
        )
    }

    @Test
    fun signalingDispatchesCallSignaling() = runTest {
        val (dispatcher, events, bridge) = harness()
        bridge.start(events, backgroundScope)
        // 先让收集器订阅：replay=0 的 SharedFlow 不会把订阅前的事件补给新订阅者。
        runCurrent()
        events.emit(WebSocketEvent.SignalingReceived("user-a", "offer", "sdp"))
        runCurrent()
        assertEquals(
            RealtimeDomainEvent.CallSignaling("user-a", "offer", "sdp"),
            dispatcher.dispatched.single()
        )
    }

    @Test
    fun serverErrorDispatchesRealtimeError() = runTest {
        val (dispatcher, events, bridge) = harness()
        bridge.start(events, backgroundScope)
        // 先让收集器订阅：replay=0 的 SharedFlow 不会把订阅前的事件补给新订阅者。
        runCurrent()
        events.emit(WebSocketEvent.ServerError("500", 30L, "boom"))
        runCurrent()
        assertEquals(
            RealtimeDomainEvent.RealtimeError("500", "boom", 30L),
            dispatcher.dispatched.single()
        )
    }

    @Test
    fun connectionEventsUpdateState() = runTest {
        val (dispatcher, events, bridge) = harness()
        bridge.start(events, backgroundScope)
        // 先让收集器订阅：replay=0 的 SharedFlow 不会把订阅前的事件补给新订阅者。
        runCurrent()
        events.emit(WebSocketEvent.Connected(true))
        events.emit(WebSocketEvent.Connected(false))
        events.emit(WebSocketEvent.Disconnected)
        events.emit(WebSocketEvent.Error(WebSocketErrorKind.CONNECTION, "boom"))
        runCurrent()
        assertEquals(
            listOf(
                RealtimeConnectionState.CONNECTED to null,
                RealtimeConnectionState.FAILED to null,
                RealtimeConnectionState.DISCONNECTED to null,
                RealtimeConnectionState.FAILED to "boom",
            ),
            dispatcher.states
        )
        assertTrue(dispatcher.dispatched.isEmpty())
    }

    @Test
    fun unhandledEventDispatchesNothing() = runTest {
        val (dispatcher, events, bridge) = harness()
        bridge.start(events, backgroundScope)
        // 先让收集器订阅：replay=0 的 SharedFlow 不会把订阅前的事件补给新订阅者。
        runCurrent()
        events.emit(WebSocketEvent.PinnedMessagesUpdated("chat-1", "user-a", emptyList()))
        runCurrent()
        assertTrue(dispatcher.dispatched.isEmpty())
        assertTrue(dispatcher.states.isEmpty())
    }
}
