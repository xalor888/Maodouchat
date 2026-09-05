package com.maodouchat.security.totp

import com.maodouchat.network.api.AuthApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 统一两步验证（TOTP 2FA）状态机与领域协调器。
 *
 * 封装完整的状态转移流：
 * Loading -> Disabled -> SetupReady -> Enabled -> Disabling -> Disabled
 * 并在异常时安全回滚状态，消除在 Composable 中分散的直接网络访问与 JSON 拼接。
 */
class TotpCoordinator(
    private val authApi: AuthApi
) {
    private val _state = MutableStateFlow<TotpState>(TotpState.Uninitialized)
    val state: StateFlow<TotpState> = _state.asStateFlow()

    suspend fun loadStatus(token: String): Result<Boolean> {
        if (token.isBlank()) {
            _state.value = TotpState.Disabled
            return Result.success(false)
        }
        _state.value = TotpState.Loading
        val result = authApi.totpStatus(token)
        return result.fold(
            onSuccess = { enabled ->
                _state.value = if (enabled) TotpState.Enabled() else TotpState.Disabled
                Result.success(enabled)
            },
            onFailure = { error ->
                // 尝试回退至 getTotpStatus 原始接口解析
                val fallback = authApi.getTotpStatus(token)
                fallback.fold(
                    onSuccess = { raw ->
                        val enabled = runCatching {
                            JSONObject(raw).optBoolean("enabled", false)
                        }.getOrDefault(false)
                        _state.value = if (enabled) TotpState.Enabled() else TotpState.Disabled
                        Result.success(enabled)
                    },
                    onFailure = {
                        _state.value = TotpState.Error(
                            message = error.message ?: "Failed to load TOTP status",
                            previousState = TotpState.Disabled
                        )
                        Result.failure(error)
                    }
                )
            }
        )
    }

    suspend fun startSetup(token: String): Result<TotpState.SetupReady> {
        if (token.isBlank()) return Result.failure(IllegalStateException("Auth token is empty"))
        _state.value = TotpState.Loading

        val result = authApi.setupTotp(token)
        return result.fold(
            onSuccess = { rawJson ->
                val parsed = runCatching {
                    val obj = JSONObject(rawJson)
                    val secret = obj.optString("secret").takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("Missing secret in TOTP response")
                    val otpauthUrl = obj.optString("otpauthUrl").takeIf { it.isNotBlank() }
                    val backupCodesList = mutableListOf<String>()
                    val backupArray = obj.optJSONArray("backupCodes")
                    if (backupArray != null) {
                        for (i in 0 until backupArray.length()) {
                            backupCodesList.add(backupArray.getString(i))
                        }
                    }
                    TotpState.SetupReady(
                        secret = secret,
                        otpauthUrl = otpauthUrl,
                        recoveryCodes = backupCodesList
                    )
                }

                parsed.fold(
                    onSuccess = { setupReady ->
                        _state.value = setupReady
                        Result.success(setupReady)
                    },
                    onFailure = { err ->
                        _state.value = TotpState.Error(
                            message = err.message ?: "Failed to parse TOTP setup response",
                            previousState = TotpState.Disabled
                        )
                        Result.failure(err)
                    }
                )
            },
            onFailure = { error ->
                _state.value = TotpState.Error(
                    message = error.message ?: "Failed to initiate TOTP setup",
                    previousState = TotpState.Disabled
                )
                Result.failure(error)
            }
        )
    }

    suspend fun confirmSetup(token: String, code: String): Result<List<String>> {
        val current = _state.value
        val previousSetup = current as? TotpState.SetupReady
        _state.value = previousSetup?.copy(isConfirming = true, error = null) ?: TotpState.Loading

        val result = authApi.confirmTotp(token, code)
        return result.fold(
            onSuccess = { recoveryCodes ->
                val enabledState = TotpState.Enabled(recoveryCodes = recoveryCodes)
                _state.value = enabledState
                Result.success(recoveryCodes)
            },
            onFailure = { error ->
                _state.value = previousSetup?.copy(
                    isConfirming = false,
                    error = error.message ?: "Invalid verification code"
                ) ?: TotpState.Error(
                    message = error.message ?: "Failed to confirm TOTP",
                    previousState = current
                )
                Result.failure(error)
            }
        )
    }

    suspend fun disable(token: String, code: String): Result<Unit> {
        val current = _state.value
        val previousEnabled = current as? TotpState.Enabled
        _state.value = previousEnabled?.copy(isWorking = true, error = null) ?: TotpState.Loading

        val result = authApi.disableTotp(token, code)
        return result.fold(
            onSuccess = {
                _state.value = TotpState.Disabled
                Result.success(Unit)
            },
            onFailure = { error ->
                _state.value = previousEnabled?.copy(
                    isWorking = false,
                    error = error.message ?: "Failed to disable TOTP"
                ) ?: TotpState.Error(
                    message = error.message ?: "Failed to disable TOTP",
                    previousState = current
                )
                Result.failure(error)
            }
        )
    }

    suspend fun regenerateCodes(token: String, code: String): Result<List<String>> {
        val current = _state.value
        val previousEnabled = current as? TotpState.Enabled
        _state.value = previousEnabled?.copy(isWorking = true, error = null) ?: TotpState.Loading

        val result = authApi.regenerateTotpCodes(token, code)
        return result.fold(
            onSuccess = { newCodes ->
                _state.value = TotpState.Enabled(recoveryCodes = newCodes)
                Result.success(newCodes)
            },
            onFailure = { error ->
                _state.value = previousEnabled?.copy(
                    isWorking = false,
                    error = error.message ?: "Failed to regenerate backup codes"
                ) ?: TotpState.Error(
                    message = error.message ?: "Failed to regenerate backup codes",
                    previousState = current
                )
                Result.failure(error)
            }
        )
    }

    fun clearError() {
        val current = _state.value
        if (current is TotpState.Error) {
            _state.value = current.previousState ?: TotpState.Disabled
        } else if (current is TotpState.SetupReady && current.error != null) {
            _state.value = current.copy(error = null)
        } else if (current is TotpState.Enabled && current.error != null) {
            _state.value = current.copy(error = null)
        }
    }

    fun reset() {
        _state.value = TotpState.Uninitialized
    }
}
