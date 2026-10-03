package com.maodouchat.session

import com.maodouchat.MaodouchatApp
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenExpiredEventPolicy
import com.maodouchat.security.LogoutStorePolicy

object TokenExpirySessionPurge {

    fun shouldHandle(event: ApiService.TokenExpiredEvent): Boolean =
        TokenExpiredEventPolicy.shouldHandle(
            eventOwnerUserId = event.ownerUserId,
            eventSessionGeneration = event.sessionGeneration,
            currentOwnerUserId = CurrentSession.snapshot().userId,
            currentSessionGeneration = MaodouchatApp.currentSessionGeneration(),
        )

    /** @return 本地会话是否确实被清掉（true = 调用方跳登录页）。 */
    suspend fun purge(event: ApiService.TokenExpiredEvent): Boolean {
        return try {
            MaodouchatApp.instance.secureSessionManager.purgeLocalSession(
                destroyEncryptedDatabase = LogoutStorePolicy.destroyEncryptedDatabase(
                    LogoutStorePolicy.Reason.TOKEN_EXPIRED
                ),
                expectedOwnerUserId = event.ownerUserId,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.e("TokenExpirySessionPurge", "Token-expiry session purge failed", error)
            false
        }
    }
}
