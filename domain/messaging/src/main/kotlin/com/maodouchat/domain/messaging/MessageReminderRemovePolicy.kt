package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReminder

// 聊天提醒单条删除：按 id 删掉指定提醒，其余项原样保留；
// 收敛提醒列表对话框与群通话上层对话框 2 处逐字相同的 filterNot。
object MessageReminderRemovePolicy {

    fun removeById(
        reminders: List<MessageReminder>,
        reminderId: String
    ): List<MessageReminder> =
        reminders.filterNot { it.id == reminderId }
}
