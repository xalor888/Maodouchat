package com.maodouchat.domain.messaging

// 消息编辑/撤回 5 分钟窗口纯计算：由 app 侧两处 withinEditWindow、两处撤回剩余分钟，
// 与 domain 内 ChatMessageMutationController 的编辑窗口守卫平移收敛；UI 侧的
// isOwn/类型判定仍在调用方，这里只留窗口算术核。
object MessageEditWindowPolicy {

    const val EDIT_WINDOW_MS: Long = 300_000L

    fun withinWindow(nowMs: Long, sentAtMs: Long): Boolean =
        nowMs - sentAtMs < EDIT_WINDOW_MS

    // 向上取整分钟：窗口刚打开显示 5 分钟，防显示 0 分钟（沿用 app 侧原公式）。
    fun remainingMinutes(nowMs: Long, sentAtMs: Long): Int =
        ((EDIT_WINDOW_MS - (nowMs - sentAtMs)) / 60_000L).toInt() + 1
}
