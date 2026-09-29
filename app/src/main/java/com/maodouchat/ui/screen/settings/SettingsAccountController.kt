package com.maodouchat.ui.screen.settings

import com.maodouchat.R
import com.maodouchat.data.repository.AccountSecurityNetworkRepository
import com.maodouchat.settings.model.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * G359：账号级动作（登出 / 退出所有设备 / 注销账号）从 `SettingsViewModel` 抽出
 * （纯搬移不改判断）——三个方法逐字搬移；accountMutationJob 的所有权随之内聚。
 *
 * 依赖全经构造器注入：scope / 状态读写 / 文案 / 会话属主校验 / 账号安全仓库。
 */
internal class SettingsAccountController(
    private val scope: CoroutineScope,
    private val currentState: () -> SettingsUiState,
    private val updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
    private val isCurrentOwner: (String) -> Boolean,
    private val accountApi: AccountSecurityNetworkRepository = AccountSecurityNetworkRepository(),
) {
    private var accountMutationJob: Job? = null

    fun logout() {
        if (accountMutationJob?.isActive == true) return
        // 9.140：快照当前账号并带归属校验 purge——此前无 expectedOwnerUserId，
        // 按钮点击到协程体执行之间换号会把新账号会话一并清掉
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        accountMutationJob = scope.launch {
            withContext(NonCancellable) {
                com.maodouchat.security.SecureSessionAccess.manager.purgeLocalSession(
                    destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                        com.maodouchat.security.LogoutStorePolicy.Reason.LOGOUT
                    ),
                    expectedOwnerUserId = ownerUserId.takeIf { it.isNotBlank() }
                )
                // 1.103：登出清空「正在输入」presence，避免残留对端状态。
                // 放在 NonCancellable 内，避免 purge 后协程取消导致清理被跳过。
                com.maodouchat.util.TypingPresenceStore.clear()
            }
            updateState { it.copy(isLoggedOut = true) }
        }
    }

    /**
     * 8.62：退出所有设备（设备丢失/被盗时远程撤销全部会话，含当前设备）。
     * 服务端已吊销当前会话——成功后本地必须 purge，否则残留假登录态。
     */
    fun logoutAllDevices() {
        if (accountMutationJob?.isActive == true) return
        val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
            return
        }
        accountMutationJob = scope.launch {
            updateState { it.copy(isLoggingOutAll = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    updateState { it.copy(isLoggingOutAll = false, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                accountApi.logoutAll().fold(
                    onSuccess = {
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        withContext(NonCancellable) {
                            com.maodouchat.security.SecureSessionAccess.manager.purgeLocalSession(
                                destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                                    com.maodouchat.security.LogoutStorePolicy.Reason.LOGOUT
                                ),
                                expectedOwnerUserId = ownerUserId
                            )
                            com.maodouchat.util.TypingPresenceStore.clear()
                        }
                        updateState { it.copy(isLoggingOutAll = false, isLoggedOut = true) }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        updateState { it.copy(isLoggingOutAll = false, errorMessage = error.message ?: text(R.string.settings_logout_all_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) updateState { it.copy(isLoggingOutAll = false) }
                throw error
            } catch (error: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    updateState { it.copy(isLoggingOutAll = false, errorMessage = error.message ?: text(R.string.settings_logout_all_failed)) }
                }
            }
        }
    }

    fun deleteAccount(password: String) {
        if (accountMutationJob?.isActive == true) return
        if (password.isBlank()) {
            updateState { it.copy(errorMessage = text(R.string.settings_enter_current_password)) }
            return
        }
        accountMutationJob = scope.launch {
            val deleteOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || deleteOwnerUserId.isBlank()) {
                updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            updateState { it.copy(isDeletingAccount = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = deleteOwnerUserId,
                )
                ) {
                    // 8.38：门禁失败复位 isDeletingAccount，否则删号弹窗永久转圈
                    updateState { it.copy(isDeletingAccount = false, errorMessage = text(R.string.error_session_expired)) }
                    return@launch
                }
                accountApi.deleteAccount(password = password).fold(
                    onSuccess = {
                        if (!isCurrentOwner(deleteOwnerUserId)) return@fold
                        val purged = withContext(kotlinx.coroutines.NonCancellable) {
                            val result = com.maodouchat.security.SecureSessionAccess.manager.purgeLocalSession(
                                destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                                    com.maodouchat.security.LogoutStorePolicy.Reason.DELETE_ACCOUNT
                                ),
                                expectedOwnerUserId = deleteOwnerUserId
                            )
                            com.maodouchat.util.TypingPresenceStore.clear()
                            result
                        }
                        // 8.33 修复：服务端账号已删除，即使本地 purge 失败也不能卡死在 loading——
                        // 提示用户手动登出（重试会因服务端已删而报错）。
                        updateState {
                            it.copy(
                                isDeletingAccount = false,
                                isLoggedOut = true,
                                successMessage = if (purged) {
                                    text(R.string.settings_account_deleted)
                                } else {
                                    text(R.string.settings_account_deleted_local_purge_failed)
                                }
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(deleteOwnerUserId)) return@fold
                        updateState {
                            it.copy(
                                isDeletingAccount = false,
                                errorMessage = error.message ?: text(R.string.settings_account_delete_failed)
                            )
                        }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(deleteOwnerUserId)) {
                    updateState { it.copy(isDeletingAccount = false) }
                }
                throw error
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)
}
