package com.maodouchat.push

import android.content.Context
import android.util.Log
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import java.util.UUID

/**
 * 安装级设备标识 + 长连接通道健康度。
 *
 * FCM 已移除：离线唤醒走 Ideaura 式 WebSocket 保活（[PushKeepAlive]）。
 * [currentDeviceId] 仍用于密聊新设备风险等本机身份，不再上报 Google token。
 */
object PushRegistrationManager {
    private const val TAG = "PushRegistration"
    private const val PREFS_NAME = "push_installation"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_REGISTERED_AT = "last_registered_at"
    private const val KEY_REGISTRATION_STATE = "registration_state"

    enum class RegistrationState {
        UNKNOWN, INITIALIZING, REGISTERED, FAILED
    }

    @Volatile
    private var registrationState: RegistrationState = RegistrationState.UNKNOWN
    @Volatile
    private var lastRegistrationError: String? = null

    /** 长连接通道始终可用（不依赖 Firebase 配置）。 */
    fun hasFirebaseConfiguration(): Boolean = true

    /** 当前进程是否具备投递条件：已登录且 WebSocket 已连上或保活已开。 */
    fun isConfigured(): Boolean = WebSocketClient.isConnected()

    fun getRegistrationState(): RegistrationState = registrationState

    @Synchronized
    fun resetRegistrationStateForAccountChange(context: Context) {
        registrationState = RegistrationState.UNKNOWN
        lastRegistrationError = null
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REGISTRATION_STATE, RegistrationState.UNKNOWN.name)
            .apply()
    }

    fun getLastRegistrationError(): String? = lastRegistrationError

    fun getLastRegisteredAt(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_REGISTERED_AT, 0L)
    }

    fun initialize(context: Context): Boolean {
        installationId(context)
        val tokenManager = TokenManager.getInstance(context)
        val loggedIn = !tokenManager.getToken().isNullOrBlank()
        if (loggedIn) {
            markKeepAliveReady(context)
            PushKeepAlive.ensureForUser(context)
        }
        Log.i(TAG, "push channel = websocket keepalive (FCM removed)")
        return true
    }

    fun refreshRegistration(context: Context) {
        val tokenManager = TokenManager.getInstance(context)
        if (tokenManager.getToken().isNullOrBlank()) return
        markKeepAliveReady(context)
        PushKeepAlive.ensureForUser(context)
    }

    suspend fun unregisterCurrentDevice(context: Context, accessToken: String) {
        if (accessToken.isBlank()) return
        PushKeepAlive.stop(context)
        resetRegistrationStateForAccountChange(context)
    }

    fun currentDeviceId(context: Context): String = installationId(context)

    @Synchronized
    private fun markKeepAliveReady(context: Context) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_REGISTERED_AT, now)
            .putString(KEY_REGISTRATION_STATE, RegistrationState.REGISTERED.name)
            .apply()
        registrationState = RegistrationState.REGISTERED
        lastRegistrationError = null
    }

    @Synchronized
    private fun installationId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf(String::isNotBlank)?.let { return it }
        val generated = UUID.randomUUID().toString()
        @Suppress("ApplySharedPref")
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }
}
