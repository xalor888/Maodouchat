package com.maodouchat.quickreply

/** 同步门禁结果 */
sealed class SyncVerdict {
    data class Allowed(val text: String, val ownerUserId: String) : SyncVerdict()
    data class Rejected(val reason: String) : SyncVerdict()
}

/** DB 级门禁结果 */
sealed class ChatGateVerdict {
    data object Allowed : ChatGateVerdict()
    data class Rejected(val reason: String) : ChatGateVerdict()
}
