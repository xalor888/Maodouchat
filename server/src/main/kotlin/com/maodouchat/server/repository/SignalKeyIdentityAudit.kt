package com.maodouchat.server.repository

// 身份不一致审计簇：原 SignalKeyBundles 顶层声明按域拆出的簇（同包调用，零行为改动）。
import com.maodouchat.server.db.AuthSessions
import com.maodouchat.server.db.RefreshTokens
import com.maodouchat.server.service.IdentitySecurityEventPolicy
import com.maodouchat.server.service.IdentitySecurityEventRecorder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.update

/**
 * B03：同槽身份不一致 → 审计事件 + 吊销当前 auth session（会话风险收敛）。
 * 必须在已持有用户行锁的事务内调用。
 */
internal fun recordIdentityMismatchAndRevokeSessionInTx(
    userId: String,
    deviceId: Int,
    authSessionId: String,
) {
    val now = System.currentTimeMillis()
    IdentitySecurityEventRecorder.recordInTx(
        userId = userId,
        action = IdentitySecurityEventPolicy.ACTION_MISMATCH,
        detail = IdentitySecurityEventPolicy.mismatchDetail(deviceId, authSessionId),
        nowMs = now,
    )
    if (IdentitySecurityEventPolicy.shouldRevokeAuthSessionOnMismatch()) {
        AuthSessions.update({ AuthSessions.id eq authSessionId }) {
            it[revokedAt] = now
            it[updatedAt] = now
        }
        RefreshTokens.update({ RefreshTokens.sessionId eq authSessionId }) {
            it[revokedAt] = now
        }
    }
}
