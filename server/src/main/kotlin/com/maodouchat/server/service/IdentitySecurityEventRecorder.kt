package com.maodouchat.server.service

import com.maodouchat.server.db.MODERATION_AUDIT_DETAIL_MAX_CHARS
import com.maodouchat.server.db.ModerationAuditLog
import org.jetbrains.exposed.sql.insert
import java.util.UUID

/**
 * B03：身份密钥安全事件写入 moderation_audit_log（与内容审核共用审计表，动作码隔离）。
 * 调用方须已在 Exposed transaction 内。
 */
object IdentitySecurityEventRecorder {

    fun recordInTx(
        userId: String,
        action: String,
        detail: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        ModerationAuditLog.insert {
            it[id] = "idsec_${UUID.randomUUID()}"
            it[actorId] = userId
            it[ModerationAuditLog.userId] = userId
            it[ModerationAuditLog.action] = action.take(40)
            it[ModerationAuditLog.detail] = detail.take(MODERATION_AUDIT_DETAIL_MAX_CHARS)
            it[createdAt] = nowMs
        }
    }
}
