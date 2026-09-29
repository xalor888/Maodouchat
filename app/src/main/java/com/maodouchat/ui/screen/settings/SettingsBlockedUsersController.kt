package com.maodouchat.ui.screen.settings

import com.maodouchat.R
import com.maodouchat.data.repository.AccountSecurityNetworkRepository
import com.maodouchat.settings.model.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * G357：「黑名单」管理（加载 / 解除）从 `SettingsViewModel` 抽出（纯搬移不改判断）——
 * 两个方法逐字搬移；两个 Job 的所有权随之内聚（原 VM 字段仅这两处使用）。
 *
 * 依赖全经构造器注入：scope / 状态读写 / 文案 / 会话属主校验 / 账号安全仓库。
 */
internal class SettingsBlockedUsersController(
    private val scope: CoroutineScope,
    private val currentState: () -> SettingsUiState,
    private val updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
    private val isCurrentOwner: (String) -> Boolean,
    private val accountApi: AccountSecurityNetworkRepository = AccountSecurityNetworkRepository(),
) {
    private var blockedUsersLoadJob: Job? = null
    private var blockedUsersMutationJob: Job? = null

    fun loadBlockedUsers() {
        blockedUsersLoadJob?.cancel()
        blockedUsersLoadJob = scope.launch {
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
                updateState {
                    it.copy(isLoadingBlockedUsers = false, errorMessage = text(R.string.error_session_expired))
                }
                return@launch
            }
            updateState { it.copy(isLoadingBlockedUsers = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    if (com.maodouchat.session.CurrentSession.snapshot().userId == ownerUserId) {
                        updateState {
                            it.copy(isLoadingBlockedUsers = false, errorMessage = text(R.string.error_session_expired))
                        }
                    }
                    return@launch
                }
                accountApi.blockedUserDetails().fold(
                    onSuccess = { users ->
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                        )
                        ) {
                            return@fold
                        }
                        updateState { it.copy(blockedUsers = users, isLoadingBlockedUsers = false) }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        updateState { it.copy(isLoadingBlockedUsers = false, errorMessage = error.message ?: text(R.string.settings_blocked_load_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    updateState { it.copy(isLoadingBlockedUsers = false) }
                }
                throw error
            }
        }
    }

    fun unblockUser(userId: String) {
        if (blockedUsersMutationJob?.isActive == true) return
        blockedUsersMutationJob = scope.launch {
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) { updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }; return@launch }
            updateState { it.copy(isUpdatingBlockedUsers = true, errorMessage = null) }
            try {
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                )
                ) {
                    return@launch
                }
                accountApi.unblock(userId = userId).fold(
                    onSuccess = {
                        if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = ownerUserId,
                        )
                        ) {
                            return@fold
                        }
                        updateState {
                            it.copy(
                                blockedUsers = it.blockedUsers.filterNot { user -> user.id == userId },
                                isUpdatingBlockedUsers = false,
                                successMessage = text(R.string.settings_unblocked)
                            )
                        }
                    },
                    onFailure = { error ->
                        if (!isCurrentOwner(ownerUserId)) return@fold
                        updateState { it.copy(isUpdatingBlockedUsers = false, errorMessage = error.message ?: text(R.string.chat_unblock_failed)) }
                    }
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) {
                    updateState { it.copy(isUpdatingBlockedUsers = false) }
                }
                throw error
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)
}
