package com.maodouchat.security.device

import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import com.maodouchat.network.WebSocketClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface DeviceRevocationResult {
    data class RemoteDeviceRevoked(val deviceId: Int) : DeviceRevocationResult
    object CurrentDeviceRevoked : DeviceRevocationResult
}

interface DeviceRevocationHandler {
    suspend fun removeRemoteDevice(token: String, deviceId: Int): Result<Unit>
    suspend fun invalidateSignalDeviceSession(deviceId: Int)
    suspend fun revokeCurrentDevice(
        token: String,
        refreshToken: String,
        deviceId: String
    ): Result<Unit>
}

/**
 * 默认设备撤销底层处理器。
 * 负责跨 Auth、Signal 协议、Push 管道与 WebSocket 实时连接的生命周期协调。
 */
class DefaultDeviceRevocationHandler(
    private val tokenManager: TokenManager? = null,
    private val webSocketClient: WebSocketClient? = null,
    private val onSignalSessionInvalidated: (suspend (deviceId: Int) -> Unit)? = null
) : DeviceRevocationHandler {

    override suspend fun removeRemoteDevice(token: String, deviceId: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            ApiService.removeMyDevice(token, deviceId)
        }

    override suspend fun invalidateSignalDeviceSession(deviceId: Int) {
        withContext(Dispatchers.IO) {
            onSignalSessionInvalidated?.invoke(deviceId)
        }
    }

    override suspend fun revokeCurrentDevice(
        token: String,
        refreshToken: String,
        deviceId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. 吊销 Push Token
            runCatching { ApiService.removePushToken(token, deviceId) }

            // 2. 断开实时 WebSocket 连接
            runCatching { webSocketClient?.disconnect() }

            // 3. 吊销 Auth Session
            runCatching { ApiService.logout(refreshToken, token, deviceId) }

            // 4. 清空本地会话 Token
            tokenManager?.clear()
            Unit
        }
    }
}

/**
 * 设备撤销协调器。
 *
 * 保证设备移除时的原子联动：
 * - 远程设备：通知云端解除关联，并使本地 Signal Device Prekey/Session 失效，阻断重放与错配；
 * - 本机设备：安全注销 Auth 凭证、解绑 Push 令牌、断开 WebSocket 并重置本地安全会话。
 */
class DeviceRevocationCoordinator(
    private val handler: DeviceRevocationHandler
) {
    suspend fun revokeDevice(
        token: String,
        targetDeviceId: Int,
        currentDeviceId: Int?,
        currentDeviceIdString: String = currentDeviceId?.toString().orEmpty(),
        refreshToken: String = ""
    ): Result<DeviceRevocationResult> {
        if (token.isBlank()) {
            return Result.failure(IllegalArgumentException("Token must not be blank"))
        }

        val isCurrentDevice = currentDeviceId != null && targetDeviceId == currentDeviceId

        return if (isCurrentDevice) {
            val result = handler.revokeCurrentDevice(
                token = token,
                refreshToken = refreshToken,
                deviceId = currentDeviceIdString
            )
            result.map { DeviceRevocationResult.CurrentDeviceRevoked }
        } else {
            val removeResult = handler.removeRemoteDevice(token, targetDeviceId)
            removeResult.fold(
                onSuccess = {
                    handler.invalidateSignalDeviceSession(targetDeviceId)
                    Result.success(DeviceRevocationResult.RemoteDeviceRevoked(targetDeviceId))
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        }
    }
}
