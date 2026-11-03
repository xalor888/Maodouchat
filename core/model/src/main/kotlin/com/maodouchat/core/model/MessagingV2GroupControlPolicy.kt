package com.maodouchat.core.model

object MessagingV2GroupControlPolicy {
    fun isGroupControl(kind: String): Boolean = kind in GROUP_CONTROL_KINDS

    fun isStale(kind: String, queuedRevision: Long?, liveRevision: Long): Boolean =
        isGroupControl(kind) && liveRevision > 0L && queuedRevision != liveRevision

    private val GROUP_CONTROL_KINDS = setOf("SENDER_KEY", "KEY_REQUEST")
}

class MessagingV2StaleGroupControlException :
    IllegalStateException("messaging_v2_stale_group_control")
