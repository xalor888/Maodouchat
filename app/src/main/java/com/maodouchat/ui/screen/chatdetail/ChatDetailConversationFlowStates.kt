package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * G335：**群公告 / 密聊开启确认 / 共享位置**这三条「会话级开关流程」的可见性与权限标记。
 *
 * 归族理由：三者都是「从溢出菜单或 composer 触发的、会话级的一次性确认」，而且都有
 * **权限与可见性两个状态**：位置这条是 `pendingLiveLocationPermission`（权限弹窗在路上）
 * 与 `showLiveLocationDuration`（拿到权限后要弹的时长选择）——这两个的顺序反了就会出现
 * 「点了共享位置却什么都没发生」。原先散在 Route 里，顺序约束只存在于读者脑内。
 *
 * 范式：原本逐个 `rememberSaveable` → 持有类自带 [Saver]。
 */
internal class ChatDetailConversationFlowState(
    showAnnouncementBanner: Boolean = true,
    showAnnouncementDialog: Boolean = false,
    showSecretChatConfirm: Boolean = false,
    showLiveLocationDuration: Boolean = false,
    pendingLiveLocationPermission: Boolean = false,
) {
    /** 群公告横幅（默认展示，用户可收起）。 */
    var showAnnouncementBanner by mutableStateOf(showAnnouncementBanner)

    /** 群公告全文弹窗。 */
    var showAnnouncementDialog by mutableStateOf(showAnnouncementDialog)

    /** 开启密聊的二次确认（不可逆操作）。 */
    var showSecretChatConfirm by mutableStateOf(showSecretChatConfirm)

    /** 位置时长选择弹窗（权限拿到之后才该出现）。 */
    var showLiveLocationDuration by mutableStateOf(showLiveLocationDuration)

    /** 已发起定位权限请求、正在等结果（决定拿到权限后是弹时长还是直接上报）。 */
    var pendingLiveLocationPermission by mutableStateOf(pendingLiveLocationPermission)

    /** 定位授权回来：消费 pending 标记，按调用方的意图决定是否弹时长。 */
    fun onLocationPermissionResult(openDurationDialog: Boolean) {
        if (openDurationDialog) showLiveLocationDuration = true
        pendingLiveLocationPermission = false
    }

    companion object {
        val Saver = listSaver<ChatDetailConversationFlowState, Any>(
            save = {
                listOf(
                    it.showAnnouncementBanner,
                    it.showAnnouncementDialog,
                    it.showSecretChatConfirm,
                    it.showLiveLocationDuration,
                    it.pendingLiveLocationPermission,
                )
            },
            restore = {
                ChatDetailConversationFlowState(
                    showAnnouncementBanner = it[0] as Boolean,
                    showAnnouncementDialog = it[1] as Boolean,
                    showSecretChatConfirm = it[2] as Boolean,
                    showLiveLocationDuration = it[3] as Boolean,
                    pendingLiveLocationPermission = it[4] as Boolean,
                )
            },
        )
    }
}

/** 与原来的逐字段 `rememberSaveable` 等价。 */
@Composable
internal fun rememberChatDetailConversationFlowState(): ChatDetailConversationFlowState =
    rememberSaveable(saver = ChatDetailConversationFlowState.Saver) { ChatDetailConversationFlowState() }

/**
 * G335：**编辑/问答草稿 + 多选集合**——需要跨旋转保住的「用户正在输入的东西」。
 *
 * 归族理由：`editDraft`（改消息时的正文）与 `fileQuestionDraft`（AI 文件问答的问题）
 * 都是**用户正在打的字**，进程重建后必须还在；`selectedMessageIds` 是批量操作的选择集，
 * 同理。它们与「弹层开没开」的区别正是「内容」与「位置」——
 * 这条边界在 AI 那一批也划过（`ChatDetailAiPanelState` 与 `ChatDetailAiResultState`）。
 *
 * `showDateJumpDialog` 放进来是因为它只服务于「跳到某天」这一个动作、与这里的日期语义相邻；
 * 单独一个布尔不值得再开一个类。
 */
internal class ChatDetailDraftState(
    editDraft: String = "",
    fileQuestionDraft: String = "",
    selectedMessageIds: Set<String> = emptySet(),
    showDateJumpDialog: Boolean = false,
) {
    var editDraft by mutableStateOf(editDraft)
    var fileQuestionDraft by mutableStateOf(fileQuestionDraft)
    var selectedMessageIds by mutableStateOf(selectedMessageIds)
    var showDateJumpDialog by mutableStateOf(showDateJumpDialog)

    /** 取消编辑时清草稿（取消后还留着上一次的正文是明显的错误行为）。 */
    fun clearEdit() {
        editDraft = ""
    }

    /** 退出多选/批量删除后清选择集。 */
    fun clearSelection() {
        selectedMessageIds = emptySet()
    }

    companion object {
        val Saver = listSaver<ChatDetailDraftState, Any>(
            save = { listOf(it.editDraft, it.fileQuestionDraft, it.selectedMessageIds.toList(), it.showDateJumpDialog) },
            restore = {
                ChatDetailDraftState(
                    editDraft = it[0] as String,
                    fileQuestionDraft = it[1] as String,
                    selectedMessageIds = (it[2] as List<String>).toSet(),
                    showDateJumpDialog = it[3] as Boolean,
                )
            },
        )
    }
}

/** 与原来的逐字段 `rememberSaveable` 等价。 */
@Composable
internal fun rememberChatDetailDraftState(): ChatDetailDraftState =
    rememberSaveable(saver = ChatDetailDraftState.Saver) { ChatDetailDraftState() }
