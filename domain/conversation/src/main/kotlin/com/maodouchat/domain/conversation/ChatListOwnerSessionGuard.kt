package com.maodouchat.domain.conversation

import com.maodouchat.core.session.OwnerSessionPolicy
import com.maodouchat.core.session.OwnerSessionSnapshot

/**
 * 会话守卫：owner 会话快照构造 / 有效性判定 / 带守卫的 Room 写事务。
 * 5 个会话列表 coordinator 共用同一份守卫。
 *
 * 端口化：当前会话 userId/token、会话代次、清理进行中与 Room 写事务全部由调用方注入，
 * 原 ChatListPorts 整袋与 CurrentSession 不再进入 domain。
 */
class ChatListOwnerSessionGuard(
    private val currentUserId: () -> String?,
    private val currentToken: () -> String?,
    private val sessionGeneration: () -> Long,
    private val isPurgeInProgress: () -> Boolean,
    private val withRoomTransaction: suspend (block: suspend () -> Boolean) -> Boolean,
) {
    val currentUserIdStr: String
        get() = currentUserId() ?: ""

    fun ownerSession(ownerUserId: String = currentUserIdStr): OwnerSessionSnapshot =
        OwnerSessionSnapshot(ownerUserId, sessionGeneration())

    fun isOwnerSessionCurrent(session: OwnerSessionSnapshot): Boolean =
        OwnerSessionPolicy.isCurrent(
            snapshot = session,
            liveUserId = currentUserId(),
            liveToken = currentToken(),
            liveSessionGeneration = sessionGeneration(),
            purgeInProgress = isPurgeInProgress(),
        )

    suspend fun withOwnerRoomWrite(
        session: OwnerSessionSnapshot,
        block: suspend () -> Unit,
    ): Boolean = withRoomTransaction {
        if (!isOwnerSessionCurrent(session)) {
            false
        } else {
            block()
            true
        }
    }
}
