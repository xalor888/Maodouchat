package com.maodouchat.ui.screen.login

import com.maodouchat.R
import com.maodouchat.data.repository.SessionNetworkRepository
import com.maodouchat.login.LoginAccess
import kotlinx.coroutines.flow.update

// Restored session: ensure Signal store is loaded before user opens chats.
// App-level cold-start also does this; this covers LoginViewModel-first paths.
// 8.49 修复：isLoggedIn 改为初始化完成后置位（与新鲜登录路径一致）——此前同步
// 置 true，密钥库加载慢时用户先进聊天页看到解密失败占位
internal suspend fun LoginViewModel.restoreSession() {
    val userId = tokenManager.getUserId()
    var token = tokenManager.getToken()
    if (userId.isNullOrBlank() || token.isNullOrBlank()) {
        _uiState.update {
            it.copy(isLoggedIn = false, errorMessage = text(R.string.error_session_expired))
        }
        return
    }
    val accessExp = tokenManager.getAccessTokenExpiresAt()
    if (accessExp > 0L && accessExp <= System.currentTimeMillis()) {
        val refreshed = runCatching {
            SessionNetworkRepository().refreshAccessToken()
        }.getOrNull()
        if (!refreshed.isNullOrBlank()) {
            token = refreshed
        } else if (!tokenManager.isLoggedIn()) {
            // 死会话（无 refresh / refresh 过期 / 服务端 401）——留在登录页并提示
            _uiState.update {
                it.copy(isLoggedIn = false, errorMessage = text(R.string.error_session_expired))
            }
            return
        }
        // 瞬时网络失败：isLoggedIn 仍 true，允许本地恢复，后续 401 再走 tokenExpired
    }
    val localCryptoReady = try {
        // Always give a locally-ready but unpublished store one upload-only retry.
        if (!LoginAccess.signalProtocol.isInitializedFor(userId)) {
            LoginAccess.signalProtocol.initialize(token, userId)
        }
        LoginAccess.signalProtocol.isLocalStoreReadyFor(userId)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        android.util.Log.w("LoginViewModel", "Signal restore for restored session failed", error)
        false
    }
    if (!localCryptoReady) {
        _uiState.update {
            it.copy(isLoggedIn = false, errorMessage = text(R.string.security_e2ee_not_ready))
        }
        return
    }
    _uiState.update { it.copy(isLoggedIn = true, requiresTotp = false, totpCode = "") }
}
