package com.maodouchat.ui.screen.settings

import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.data.repository.AccountSecurityNetworkRepository
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

fun SettingsViewModel.changePassword(old: String, new: String, confirm: String, onSuccess: () -> Unit) {
    if (!passwordChangeMutex.tryLock()) return
    if (old.isBlank()) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.settings_enter_old_password)) }
        return
    }
    if (new.length < 6) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.settings_new_password_length)) }
        return
    }
    if (new != confirm) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.settings_password_mismatch)) }
        return
    }
    val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
    if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
        passwordChangeMutex.unlock()
        _uiState.update { it.copy(errorMessage = text(R.string.error_session_expired)) }
        return
    }
    while (true) {
        val state = _uiState.value
        if (state.isSaving) {
            passwordChangeMutex.unlock()
            return
        }
        if (_uiState.compareAndSet(state, state.copy(isSaving = true, errorMessage = null))) break
    }
    val job = viewModelScope.launch {
        try {
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) {
                // 8.38：门禁失败需复位 isSaving，否则弹窗转圈且无法关闭
                _uiState.update { it.copy(isSaving = false, errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            AccountSecurityNetworkRepository().changePassword(oldPassword = old, newPassword = new).fold(
                onSuccess = {
                    if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                    )
                    ) {
                        // 8.38：成功路径二次门禁失败也需复位，否则弹窗无法关闭
                        _uiState.update { it.copy(isSaving = false) }
                        return@fold
                    }
                    // 服务端已 revoke 全部 token + 断 WS；本地必须立刻清会话，
                    // 否则下一次 401→refresh 失败会走 tokenExpired 并 destroyEncryptedDatabase。
                    val purged = withContext(kotlinx.coroutines.NonCancellable) {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                        )
                        ) {
                            return@withContext false
                        }
                        // 9.140：带账号归属校验 purge——此前无 expectedOwnerUserId，
                        // 断连窗口内换号会把新账号的会话一并清掉
                        com.maodouchat.security.SecureSessionAccess.manager
                            .purgeLocalSession(
                                destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                                    com.maodouchat.security.LogoutStorePolicy.Reason.LOGOUT
                                ),
                                expectedOwnerUserId = ownerUserId
                            )
                    }
                    if (!purged) {
                        _uiState.update { it.copy(isSaving = false) }
                        return@fold
                    }
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            isLoggedOut = true,
                            successMessage = text(R.string.settings_password_changed)
                        )
                    }
                    onSuccess()
                },
                onFailure = { error ->
                    if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                    )
                    ) {
                        _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: text(R.string.settings_password_change_failed)) }
                    }
                }
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) {
                _uiState.update { it.copy(isSaving = false) }
            }
            throw error
        } catch (error: Throwable) {
            if (com.maodouchat.security.BackgroundSessionGate.mayContinue(
                expectedUserId = ownerUserId,
            )
            ) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = error.message ?: text(R.string.settings_password_change_failed),
                    )
                }
            }
        }
    }
    job.invokeOnCompletion { passwordChangeMutex.unlock() }
}

/**
 * 改密码互斥锁（G121 从 `SettingsSubViewModels.kt` 删档时按 git 记录恢复）。
 * 只服务 `SettingsViewModel.changePassword`——防止重复提交。
 */
private val passwordChangeMutex = Mutex()
