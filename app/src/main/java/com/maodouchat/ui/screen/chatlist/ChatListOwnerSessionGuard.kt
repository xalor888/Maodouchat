package com.maodouchat.ui.screen.chatlist

import com.maodouchat.chatlist.ChatListPorts
import com.maodouchat.ui.OwnerSessionPolicy
import com.maodouchat.ui.OwnerSessionSnapshot

/**
 * 会话守卫：owner 会话快照构造 / 有效性判定 / 带守卫的 Room 写事务。
 * 从 ChatListViewModel 逐字搬出，5 个 coordinator 共用同一份守卫。
 */
internal class ChatListOwnerSessionGuard(
    private val ports: ChatListPorts,
) {
    val currentUserIdStr: String
        get() = com.maodouchat.session.CurrentSession.snapshot().userId ?: ""

    fun ownerSession(ownerUserId: String = currentUserIdStr): OwnerSessionSnapshot =
        OwnerSessionSnapshot(ownerUserId, ports.sessionGeneration())

    fun isOwnerSessionCurrent(session: OwnerSessionSnapshot): Boolean =
        OwnerSessionPolicy.isCurrent(
            snapshot = session,
            liveUserId = com.maodouchat.session.CurrentSession.snapshot().userId,
            liveToken = com.maodouchat.session.CurrentSession.snapshot().token,
            liveSessionGeneration = ports.sessionGeneration(),
            purgeInProgress = ports.isPurgeInProgress(),
        )

    suspend fun withOwnerRoomWrite(
        session: OwnerSessionSnapshot,
        block: suspend () -> Unit,
    ): Boolean = ports.withRoomTransaction {
        if (!isOwnerSessionCurrent(session)) {
            false
        } else {
            block()
            true
        }
    }
}
