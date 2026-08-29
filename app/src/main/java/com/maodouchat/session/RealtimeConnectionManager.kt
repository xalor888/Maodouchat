package com.maodouchat.session

import com.maodouchat.network.WebSocketEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

interface RealtimeConnectionManager {
    val events: SharedFlow<WebSocketEvent>

    fun start(
        serverUrl: String,
        accessToken: String,
        owner: SessionContext,
        reconnect: Boolean = false,
    ): Boolean

    fun stop(owner: SessionContext): Boolean
    fun stopCurrent()
    fun isConnected(owner: SessionContext): Boolean
    fun sendRaw(owner: SessionContext, text: String): Boolean
    fun sendTyping(owner: SessionContext, chatId: String, isTyping: Boolean): Boolean
    fun sendPresence(owner: SessionContext, foreground: Boolean): Boolean
}

interface RealtimeTransport {
    val events: SharedFlow<RealtimeTransportEvent>

    fun open(serverUrl: String, accessToken: String, reconnect: Boolean): Long?
    fun disconnect()
    fun isConnected(): Boolean
    fun sendRaw(text: String): Boolean
    fun sendTyping(chatId: String, isTyping: Boolean): Boolean
    fun sendPresence(foreground: Boolean): Boolean
}

data class RealtimeTransportEvent(
    val connectionGeneration: Long,
    val event: WebSocketEvent,
)

class AccountScopedRealtimeConnectionManager(
    private val sessionContextProvider: SessionContextProvider,
    private val transport: RealtimeTransport,
    scope: CoroutineScope,
) : RealtimeConnectionManager {
    private val lock = Any()

    @Volatile
    private var activeOwner: SessionContext? = null

    @Volatile
    private var activeConnectionGeneration: Long? = null

    override val events: SharedFlow<WebSocketEvent> = transport.events
        .filter { transportEvent ->
            val owner = activeOwner
            owner != null &&
                sessionContextProvider.isCurrent(owner) &&
                activeConnectionGeneration == transportEvent.connectionGeneration
        }
        .map { it.event }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    init {
        scope.launch {
            sessionContextProvider.contexts.collect { current ->
                synchronized(lock) {
                    val owner = activeOwner
                    if (owner != null && owner != current) {
                        activeOwner = null
                        activeConnectionGeneration = null
                        transport.disconnect()
                    }
                }
            }
        }
    }

    override fun start(
        serverUrl: String,
        accessToken: String,
        owner: SessionContext,
        reconnect: Boolean,
    ): Boolean = synchronized(lock) {
        if (serverUrl.isBlank() || !sessionContextProvider.isAccessTokenCurrent(owner, accessToken)) {
            return@synchronized false
        }
        val previousOwner = activeOwner
        if (reconnect && previousOwner != owner) return@synchronized false
        if (previousOwner != null && previousOwner != owner) transport.disconnect()
        activeOwner = owner
        val connectionGeneration = transport.open(serverUrl, accessToken, reconnect)
        if (connectionGeneration == null) {
            activeOwner = null
            activeConnectionGeneration = null
            return@synchronized false
        }
        activeConnectionGeneration = connectionGeneration
        true
    }

    override fun stop(owner: SessionContext): Boolean = synchronized(lock) {
        if (activeOwner != owner) return@synchronized false
        activeOwner = null
        activeConnectionGeneration = null
        transport.disconnect()
        true
    }

    override fun stopCurrent() {
        synchronized(lock) {
            activeOwner = null
            activeConnectionGeneration = null
            transport.disconnect()
        }
    }

    override fun isConnected(owner: SessionContext): Boolean =
        ownsActiveConnection(owner) && transport.isConnected()

    override fun sendRaw(owner: SessionContext, text: String): Boolean =
        ownsActiveConnection(owner) && transport.sendRaw(text)

    override fun sendTyping(owner: SessionContext, chatId: String, isTyping: Boolean): Boolean =
        ownsActiveConnection(owner) && transport.sendTyping(chatId, isTyping)

    override fun sendPresence(owner: SessionContext, foreground: Boolean): Boolean =
        ownsActiveConnection(owner) && transport.sendPresence(foreground)

    private fun ownsActiveConnection(owner: SessionContext): Boolean =
        activeOwner == owner && sessionContextProvider.isCurrent(owner)
}
