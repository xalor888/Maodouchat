package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * G335：**会话级设置与提醒/定时这一组弹窗的开合**。
 *
 * 七个字段看着杂，其实都挂在会话顶栏的溢出菜单下面，而且**互相排斥地开**：
 * 消失时间、免打扰时段、静音至、提醒列表、定时发送、定时列表，以及「改哪条定时消息」
 * （`rescheduleTargetId` 非空时定时弹窗是「编辑」而不是「新建」）。原先散在 Route 里 7 行声明 +
 * 各处 lambda 里手写 `= false`，读的人要跨几百行才能确认「关掉这一组时有没有漏关」。
 *
 * 与搜索/群通话同一范式：原本逐个 `rememberSaveable`，所以持有类自带 [Saver]
 * （`rescheduleTargetId` 是可空字符串，用空串表示 null），
 * 并用 [rememberChatDetailScheduleState] 创建——保存语义不变。
 */
internal class ChatDetailScheduleState(
    showDisappearDialog: Boolean = false,
    showQuietHoursDialog: Boolean = false,
    showSilentUntilDialog: Boolean = false,
    showReminderList: Boolean = false,
    showScheduleDialog: Boolean = false,
    showScheduledList: Boolean = false,
    rescheduleTargetId: String? = null,
) {
    var showDisappearDialog by mutableStateOf(showDisappearDialog)
    var showQuietHoursDialog by mutableStateOf(showQuietHoursDialog)
    var showSilentUntilDialog by mutableStateOf(showSilentUntilDialog)
    var showReminderList by mutableStateOf(showReminderList)
    var showScheduleDialog by mutableStateOf(showScheduleDialog)
    var showScheduledList by mutableStateOf(showScheduledList)

    /** 正在改的定时消息 id（null = 新建）。 */
    var rescheduleTargetId by mutableStateOf(rescheduleTargetId)

    /**
     * 打开「定时发送」：`targetId` 非空表示改这一条。
     * 原先调用点要写两行（关列表 + 记 id + 开弹窗），三件事一起做才对。
     */
    fun openSchedule(targetId: String? = null) {
        showScheduledList = false
        rescheduleTargetId = targetId
        showScheduleDialog = true
        showReminderList = false
    }

    /** 这一组里除「消失时间」外的弹窗全部收起（从溢出菜单进另一项时用）。 */
    fun closeAll() {
        showDisappearDialog = false
        showQuietHoursDialog = false
        showSilentUntilDialog = false
        showReminderList = false
        showScheduleDialog = false
        showScheduledList = false
        rescheduleTargetId = null
    }

    companion object {
        val Saver = listSaver<ChatDetailScheduleState, Any>(
            save = {
                listOf(
                    it.showDisappearDialog,
                    it.showQuietHoursDialog,
                    it.showSilentUntilDialog,
                    it.showReminderList,
                    it.showScheduleDialog,
                    it.showScheduledList,
                    it.rescheduleTargetId ?: "",
                )
            },
            restore = {
                ChatDetailScheduleState(
                    showDisappearDialog = it[0] as Boolean,
                    showQuietHoursDialog = it[1] as Boolean,
                    showSilentUntilDialog = it[2] as Boolean,
                    showReminderList = it[3] as Boolean,
                    showScheduleDialog = it[4] as Boolean,
                    showScheduledList = it[5] as Boolean,
                    rescheduleTargetId = (it[6] as String).takeIf(String::isNotEmpty),
                )
            },
        )
    }
}

/** 与原来的逐字段 `rememberSaveable` 等价（含旋转/进程重建恢复）。 */
@Composable
internal fun rememberChatDetailScheduleState(): ChatDetailScheduleState =
    rememberSaveable(saver = ChatDetailScheduleState.Saver) { ChatDetailScheduleState() }
