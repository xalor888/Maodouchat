package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.maodouchat.util.MessageReminderStore.MessageReminder

/**
 * G335：**会话级设置与提醒/定时这一组弹窗的开合与对话框内数据**。
 *
 * 七个开关看着杂，其实都挂在会话顶栏的溢出菜单下面，而且**互相排斥地开**：
 * 消失时间、免打扰时段、静音至、提醒列表、定时发送、定时列表，以及「改哪条定时消息」
 * （`rescheduleTargetId` 非空时定时弹窗是「编辑」而不是「新建」）。原先散在 Route 里 7 行声明 +
 * 各处 lambda 里手写 `= false`，读的人要跨几百行才能确认「关掉这一组时有没有漏关」。
 *
 * 第二十二批又收进两个对话框**局部**状态（此前是对话框 `if` 块里的 `remember`）：
 * - `reminderList`：提醒列表对话框展示的数据。加载时机由 Route 侧保证——打开瞬间在点击处
 *   同步加载（首帧即有数据，与原 `remember` 初始化一致）；旋转重建 / 打开期间切会话时由
 *   Route 的 `LaunchedEffect(reminderChatId)` 重载（与原 `remember` 的重算键等价）。
 *   本类不碰 ViewModel/Store，只存数据。
 * - `rescheduleTextDraft`：重排定时消息时可编辑的文案草稿。原先是 `remember(targetId)`，
 *   转屏会按当前待发文案重算（丢掉转屏前已输入的编辑）；收进本类后**进 Saver**，
 *   转屏保留编辑中草稿——与第十批「编辑/问答草稿跨旋转保住」同一口径，是有意的改进。
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
    rescheduleTextDraft: String = "",
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
     * 重排定时消息时可编辑的文案草稿（[rescheduleTargetId] 非空时有效）。
     * 由 [beginReschedule] 用该条消息的当前待发文案初始化，之后随用户编辑；
     * 进 Saver，转屏保留（见类 KDoc）。
     */
    var rescheduleTextDraft by mutableStateOf(rescheduleTextDraft)

    /**
     * 稍后提醒列表对话框当前展示的列表（对话框开合由 [showReminderList] 控制）。
     * 瞬态：不进 Saver；旋转重建后由 Route 侧重载（原 `remember` 的行为）。
     */
    var reminderList by mutableStateOf(emptyList<MessageReminder>())

    /**
     * 打开「稍后提醒」列表：调用方传入打开瞬间从 Store 读到的列表。
     * 原先这行加载写在对话框 `if` 块的 `remember` 初始化里（首帧即有数据），
     * 保持同步调用，语义不变。
     */
    fun openReminderList(reminders: List<MessageReminder>) {
        reminderList = reminders
        showReminderList = true
    }

    /** 关闭「稍后提醒」列表（同时丢掉列表数据，与原 `remember` 随对话框销毁而丢弃一致）。 */
    fun closeReminderList() {
        showReminderList = false
        reminderList = emptyList()
    }

    /**
     * 开始重排某条定时消息：记 id，并用其当前待发文案初始化可编辑草稿。
     * 调用方（定时列表 / 定时横幅的重排入口）传入当时查到的文案，
     * 与原 `remember(targetId)` 的初值算法一致。
     */
    fun beginReschedule(targetId: String, currentText: String) {
        rescheduleTargetId = targetId
        rescheduleTextDraft = currentText
    }

    /** 重排结束（发送/改期/关闭）：清 id 与草稿。 */
    fun clearReschedule() {
        rescheduleTargetId = null
        rescheduleTextDraft = ""
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
                    it.rescheduleTextDraft,
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
                    rescheduleTextDraft = it[7] as String,
                )
            },
        )
    }
}

/** 与原来的逐字段 `rememberSaveable` 等价（含旋转/进程重建恢复）。 */
@Composable
internal fun rememberChatDetailScheduleState(): ChatDetailScheduleState =
    rememberSaveable(saver = ChatDetailScheduleState.Saver) { ChatDetailScheduleState() }
