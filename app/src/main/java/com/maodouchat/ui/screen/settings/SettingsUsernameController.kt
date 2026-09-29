package com.maodouchat.ui.screen.settings

import com.maodouchat.R
import com.maodouchat.data.repository.AccountSecurityNetworkRepository
import com.maodouchat.settings.model.SettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * G361：用户名一族（公开主页 URL 加载 / 编辑器开关 / 保存）从 `SettingsViewModel` 抽出
 * （纯搬移不改判断）——五个方法逐字搬移。
 *
 * 依赖全经构造器注入：scope / 状态读写 / 文案 / 会话属主校验 / 账号安全仓库。
 */
internal class SettingsUsernameController(
    private val scope: CoroutineScope,
    private val currentState: () -> SettingsUiState,
    private val updateState: ((SettingsUiState) -> SettingsUiState) -> Unit,
    private val textFn: (Int, Array<out Any>) -> String,
    private val isCurrentOwner: (String) -> Boolean,
    private val accountApi: AccountSecurityNetworkRepository = AccountSecurityNetworkRepository(),
) {
    fun loadPublicProfileUrl() {
        scope.launch {
            if (!com.maodouchat.session.CurrentSession.hasSession()) return@launch
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!isCurrentOwner(ownerUserId)) return@launch
            accountApi.currentUserPublic().onSuccess { resp ->
                if (!isCurrentOwner(ownerUserId)) return@onSuccess
                updateState {
                    it.copy(
                        publicProfileUrl = resp.publicProfileUrl,
                        userUsername = resp.user?.username
                    )
                }
            }
        }
    }

    fun openUsernameEditor() {
        updateState {
            it.copy(
                showUsernameDialog = true,
                editUsername = it.userUsername ?: ""
            )
        }
    }

    fun closeUsernameEditor() {
        updateState { it.copy(showUsernameDialog = false) }
    }

    fun onEditUsernameChange(value: String) {
        // 只允许字母、数字、下划线、连字符，小写
        val filtered = value.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }
            .take(50).lowercase().removePrefix("@")
        updateState { it.copy(editUsername = filtered) }
    }

    fun saveUsername() {
        val username = currentState().editUsername.trim()
        if (username.length < 3) {
            updateState { it.copy(errorMessage = text(R.string.settings_username_too_short)) }
            return
        }
        if (!username.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            updateState { it.copy(errorMessage = text(R.string.settings_username_invalid)) }
            return
        }
        scope.launch {
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (!com.maodouchat.session.CurrentSession.hasSession() || ownerUserId.isBlank()) {
                updateState { it.copy(errorMessage = text(R.string.error_session_expired)) }
                return@launch
            }
            updateState { it.copy(isSaving = true, errorMessage = null) }
            try {
                if (!isCurrentOwner(ownerUserId)) return@launch
                // 8.37 修复：此前两分支的 Result 被当表达式语句丢弃、无条件 success——
                // 用户名重复/非法/网络失败被吞掉还显示「已更新」。改为真实返回。
                val result = if (username.isBlank()) {
                    accountApi.clearUsername().map { username }
                } else {
                    accountApi.setUsername(username = username).map { it.username ?: username }
                }
                result.onSuccess {
                    if (!isCurrentOwner(ownerUserId)) return@onSuccess
                    updateState {
                        it.copy(
                            isSaving = false,
                            showUsernameDialog = false,
                            userUsername = username,
                            successMessage = text(R.string.settings_username_updated)
                        )
                    }
                    loadPublicProfileUrl()
                }
                result.onFailure { error ->
                    if (!isCurrentOwner(ownerUserId)) return@onFailure
                    updateState {
                        it.copy(
                            isSaving = false,
                            errorMessage = error.message ?: text(R.string.settings_username_update_failed)
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (isCurrentOwner(ownerUserId)) updateState { it.copy(isSaving = false) }
                throw e
            } catch (e: Exception) {
                if (isCurrentOwner(ownerUserId)) {
                    updateState { it.copy(isSaving = false, errorMessage = e.message ?: text(R.string.settings_username_update_failed)) }
                }
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = textFn(id, args)
}
