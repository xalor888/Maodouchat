package com.maodouchat.domain.conversation

// 未读数角标文案：底部导航角标、聊天详情待读新消息角标、会话文件夹芯片角标
// 三处 app 侧逐字相同的 `> 99 → "99+"` 收敛到此处。
object UnreadBadgePolicy {

    private const val MAX_SHOWN_COUNT = 99

    // 超过上限只显示 "99+"，其余原样显示数字（与原行内逻辑逐字一致）。
    fun format(count: Int): String =
        if (count > MAX_SHOWN_COUNT) "99+" else count.toString()
}
