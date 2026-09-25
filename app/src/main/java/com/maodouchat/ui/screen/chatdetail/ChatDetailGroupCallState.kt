package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.maodouchat.webrtc.CallType

/**
 * G335：**发起群通话前的那一段选择**（通话类型 → 选成员 → 搜索成员）。
 *
 * 归成一族：它们是同一个三步流程的状态，且有隐含的先后关系——`pendingGroupCallType`
 * 是第一步的结果（音频/视频），`showGroupCallMemberDialog` 是第二步的开关，
 * `selectedGroupCallMemberIds` / `groupCallMemberSearch` 是第二步里的选择与过滤。
 * 只有 `showGroupCallTypeDialog` 与它们不同：那是入口开关。放在一起是因为调用点
 * 就在相邻的几处 lambda 里，分开只会让「这一步做完了下一步弹什么」更难读。
 *
 * 与 `ChatDetailSearchState` 同一范式：**这一族原本是 `rememberSaveable`**，
 * 所以持有类自带 [Saver]（`CallType` 是枚举，按 `name` 存），并用
 * [rememberChatDetailGroupCallState] 创建——保存语义与原来逐字段 `rememberSaveable` 等价
 * （用户选到一半被系统杀掉再回来，选中的成员还在）。
 */
internal class ChatDetailGroupCallState(
    showGroupCallTypeDialog: Boolean = false,
    showGroupCallMemberDialog: Boolean = false,
    pendingGroupCallType: CallType? = null,
    selectedGroupCallMemberIds: Set<String> = emptySet(),
    groupCallMemberSearch: String = "",
) {
    var showGroupCallTypeDialog by mutableStateOf(showGroupCallTypeDialog)
    var showGroupCallMemberDialog by mutableStateOf(showGroupCallMemberDialog)
    var pendingGroupCallType by mutableStateOf(pendingGroupCallType)
    var selectedGroupCallMemberIds by mutableStateOf(selectedGroupCallMemberIds)
    var groupCallMemberSearch by mutableStateOf(groupCallMemberSearch)

    /** 选了通话类型 → 关掉类型弹窗、记住类型、进入选成员。 */
    fun chooseType(type: CallType) {
        pendingGroupCallType = type
        showGroupCallTypeDialog = false
        selectedGroupCallMemberIds = emptySet()
        groupCallMemberSearch = ""
        showGroupCallMemberDialog = true
    }

    /** 收起整个流程（取消或拨出后调用）——五个字段必须一起清，只清一个会留下「下一次点开会带着上次的选择」。 */
    fun reset() {
        showGroupCallTypeDialog = false
        showGroupCallMemberDialog = false
        pendingGroupCallType = null
        selectedGroupCallMemberIds = emptySet()
        groupCallMemberSearch = ""
    }

    companion object {
        val Saver = listSaver<ChatDetailGroupCallState, Any>(
            save = {
                listOf(
                    it.showGroupCallTypeDialog,
                    it.showGroupCallMemberDialog,
                    it.pendingGroupCallType?.name ?: "",
                    it.selectedGroupCallMemberIds.toList(),
                    it.groupCallMemberSearch,
                )
            },
            restore = {
                @Suppress("UNCHECKED_CAST")
                ChatDetailGroupCallState(
                    showGroupCallTypeDialog = it[0] as Boolean,
                    showGroupCallMemberDialog = it[1] as Boolean,
                    pendingGroupCallType = (it[2] as String).takeIf(String::isNotEmpty)?.let(CallType::valueOf),
                    selectedGroupCallMemberIds = (it[3] as List<String>).toSet(),
                    groupCallMemberSearch = it[4] as String,
                )
            },
        )
    }
}

/** 与原来的逐字段 `rememberSaveable` 等价（含旋转/进程重建恢复）。 */
@Composable
internal fun rememberChatDetailGroupCallState(): ChatDetailGroupCallState =
    rememberSaveable(saver = ChatDetailGroupCallState.Saver) { ChatDetailGroupCallState() }
