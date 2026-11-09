package com.maodouchat.domain.messaging

/** 出件箱事件入队时的终端墓碑目标：只有撤回/删除事件才落墓碑。 */
data class TerminalTombstone(val targetMessageId: String, val kind: String)

fun terminalTombstone(action: String, targetMessageId: String): TerminalTombstone? {
    val kind = terminalTombstoneKind(action) ?: return null
    return TerminalTombstone(targetMessageId = targetMessageId, kind = kind)
}
