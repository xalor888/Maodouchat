package com.maodouchat.session

import com.maodouchat.MaodouchatApp
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenExpiredEventPolicy
import com.maodouchat.security.LogoutStorePolicy

/**
 * Token 失效（401 / WS 1008 踢线 / 设备被删）后的本地会话清理（U02 延伸：自 `NavGraph`
 * 迁出）。
 *
 * 语义（逐字对齐原收集器）：
 * - [shouldHandle]：账号与世代都要匹配（登出/换号后送达的旧事件必须丢弃）；
 * - [purge]：清本地会话（可含加密库销毁，按 [LogoutStorePolicy.Reason.TOKEN_EXPIRED] 决策），
 *   失败只记日志不抛（原实现同样吞异常并返回 false）；`CancellationException` 必须重抛
 *   （项目惯例：取消不是失败）。
 *
 * 调用方（UI）只在 [purge] 返回 true 时跳登录页。
 */
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
