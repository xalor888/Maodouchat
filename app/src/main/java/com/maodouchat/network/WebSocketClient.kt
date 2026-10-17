package com.maodouchat.network

import com.maodouchat.session.AccountScopedRealtimeConnectionManager
import com.maodouchat.session.RealtimeConnectionManager
import com.maodouchat.session.TokenManagerSessionContextProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow

/**
 * Compatibility facade for existing callers. Account ownership and lifecycle are enforced by
 * [RealtimeConnectionManager]; callers cannot retain a socket across a session generation change.
 */
object WebSocketClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun manager(): RealtimeConnectionManager? {
        val tokenManager = TokenManager.getInstanceOrNull() ?: return null
        return ManagerHolder.getOrCreate(tokenManager)
    }

    val events: SharedFlow<WebSocketEvent>
        get() = manager()?.events ?: EmptyEvents.flow

    /** Must be called before any process-long collector captures [events]. */
    fun install(connectionManager: RealtimeConnectionManager) {
        ManagerHolder.install(connectionManager)
    }

    fun connect(
        newServerUrl: String,
        newToken: String,
        isReconnect: Boolean = false,
        reconnectSession: Long? = null,
    ) {
        val tokenManager = TokenManager.getInstanceOrNull() ?: return
        val owner = tokenManager.currentSessionContext() ?: return
        if (reconnectSession != null && reconnectSession != owner.generation) return
        manager()?.start(newServerUrl, newToken, owner, reconnect = isReconnect)
    }

    fun disconnect() {
        manager()?.stopCurrent() ?: WebSocketTransport.disconnect()
    }

    fun isConnected(): Boolean {
        val owner = TokenManager.getInstanceOrNull()?.currentSessionContext() ?: return false
        return manager()?.isConnected(owner) == true
    }

    fun sendRaw(text: String): Boolean {
        val owner = TokenManager.getInstanceOrNull()?.currentSessionContext() ?: return false
        return manager()?.sendRaw(owner, text) == true
    }

    fun sendTyping(chatId: String, isTyping: Boolean): Boolean {
        val owner = TokenManager.getInstanceOrNull()?.currentSessionContext() ?: return false
        return manager()?.sendTyping(owner, chatId, isTyping) == true
    }

    fun sendPresence(foreground: Boolean): Boolean {
        val owner = TokenManager.getInstanceOrNull()?.currentSessionContext() ?: return false
        return manager()?.sendPresence(owner, foreground) == true
    }

    private object ManagerHolder {
        @Volatile @Suppress("StaticFieldLeak") // TokenManager 只持有 applicationContext（getInstance 内已转换）
        private var tokenManager: TokenManager? = null

        @Volatile
        private var manager: RealtimeConnectionManager? = null

        fun install(connectionManager: RealtimeConnectionManager) {
            synchronized(this) {
                manager?.stopCurrent()
                tokenManager = TokenManager.getInstanceOrNull()
                manager = connectionManager
            }
        }

        fun getOrCreate(currentTokenManager: TokenManager): RealtimeConnectionManager {
            manager?.takeIf { tokenManager === currentTokenManager }?.let { return it }
            return synchronized(this) {
                manager?.takeIf { tokenManager === currentTokenManager } ?: run {
                    tokenManager = currentTokenManager
                    AccountScopedRealtimeConnectionManager(
                        sessionContextProvider = TokenManagerSessionContextProvider(currentTokenManager),
                        transport = WebSocketTransport,
                        scope = scope,
                    ).also { manager = it }
                }
            }
        }
    }

    private object EmptyEvents {
        val flow = kotlinx.coroutines.flow.MutableSharedFlow<WebSocketEvent>(replay = 0)
    }
}
