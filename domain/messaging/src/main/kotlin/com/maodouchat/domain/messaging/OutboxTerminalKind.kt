package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2EventAction

/** 终端事件对应的 tombstone 种类：只有撤回/删除会落墓碑，其余事件不写 tombstone。 */
fun terminalTombstoneKind(action: String): String? = when (action) {
    MessagingV2EventAction.DELETE -> "DELETE"
    MessagingV2EventAction.REVOKE -> "REVOKE"
    else -> null
}
