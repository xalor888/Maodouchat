package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * G335：**聊天锁（PIN）设置流程**的三个弹窗 + 三份输入。
 *
 * 归族理由：这是「设锁 / 关锁 / 忘了 PIN」三条互斥路径，共用两份输入（设置时的 PIN 与确认、
 * 关闭时的 PIN）。原先散在 Route 里 6 行声明，加上三处 `setLockError`，读的人要拼出
 * 「哪条路径用哪份输入」；收进来后 [reset] 一次清干净——否则取消一次再进来会看到上次输入的 PIN
 * （那既是体验问题，也是隐私问题：**明文 PIN 不该在取消后留在状态里**）。
 *
 * 第十七批归位：`setLockError`（设置锁对话框的错误文案）——PIN 长度不足 / 两次输入不一致
 * 时的提示文案，是同一条「设锁」路径的瞬态文案。**不进 Saver**：原来就是
 * `remember { mutableStateOf(null) }`，转屏重置为 null 的语义保持不变。
 *
 * 范式：原本逐个 `rememberSaveable` → 持有类自带 [Saver]，
 * 用 [rememberChatDetailChatLockState] 创建，保存语义不变（转屏时输入不丢）。
 */
internal class ChatDetailChatLockState(
    showSetChatLock: Boolean = false,
    showDisableChatLock: Boolean = false,
    showForgotChatLockConfirm: Boolean = false,
    setLockPinDraft: String = "",
    setLockPinConfirm: String = "",
    disableLockPinDraft: String = "",
) {
    var showSetChatLock by mutableStateOf(showSetChatLock)
    var showDisableChatLock by mutableStateOf(showDisableChatLock)
    var showForgotChatLockConfirm by mutableStateOf(showForgotChatLockConfirm)
    var setLockPinDraft by mutableStateOf(setLockPinDraft)
    var setLockPinConfirm by mutableStateOf(setLockPinConfirm)
    var disableLockPinDraft by mutableStateOf(disableLockPinDraft)

    /** 设置锁对话框的错误文案（第十七批归位；瞬态，不进 Saver）。 */
    var setLockError by mutableStateOf<String?>(null)

    /** 三条路径都收起，并**清掉输入里的明文 PIN**与错误文案（取消/成功后调用）。 */
    fun reset() {
        showSetChatLock = false
        showDisableChatLock = false
        showForgotChatLockConfirm = false
        setLockPinDraft = ""
        setLockPinConfirm = ""
        disableLockPinDraft = ""
        setLockError = null
    }

    companion object {
        val Saver = listSaver<ChatDetailChatLockState, Any>(
            save = {
                listOf(
                    it.showSetChatLock,
                    it.showDisableChatLock,
                    it.showForgotChatLockConfirm,
                    it.setLockPinDraft,
                    it.setLockPinConfirm,
                    it.disableLockPinDraft,
                )
            },
            restore = {
                ChatDetailChatLockState(
                    showSetChatLock = it[0] as Boolean,
                    showDisableChatLock = it[1] as Boolean,
                    showForgotChatLockConfirm = it[2] as Boolean,
                    setLockPinDraft = it[3] as String,
                    setLockPinConfirm = it[4] as String,
                    disableLockPinDraft = it[5] as String,
                )
            },
        )
    }
}

/** 与原来的逐字段 `rememberSaveable` 等价。 */
@Composable
internal fun rememberChatDetailChatLockState(): ChatDetailChatLockState =
    rememberSaveable(saver = ChatDetailChatLockState.Saver) { ChatDetailChatLockState() }

/**
 * G335：**联系人卡片/资料层的三层入口**（操作面板 → 联系人资料 → 发送名片时的选人）。
 *
 * 归族理由：三者是同一个「在会话里操作某个人」的入口链，且都以**对方 userId** 为上下文
 * （`showContactProfile` 打开的是当前联系人的资料，`showContactCardPicker` 是给 composer 选名片对象）。
 * 原先三个布尔散在 Route 里，看不出它们是一组入口。
 *
 * 范式同上（原本 `rememberSaveable`）。
 */
internal class ChatDetailContactSheetState(
    showContactActions: Boolean = false,
    showContactProfile: Boolean = false,
    showContactCardPicker: Boolean = false,
) {
    var showContactActions by mutableStateOf(showContactActions)
    var showContactProfile by mutableStateOf(showContactProfile)
    var showContactCardPicker by mutableStateOf(showContactCardPicker)

    /** 三层一起收起（返回会话/选中某个人之后）。 */
    fun reset() {
        showContactActions = false
        showContactProfile = false
        showContactCardPicker = false
    }

    companion object {
        val Saver = listSaver<ChatDetailContactSheetState, Any>(
            save = { listOf(it.showContactActions, it.showContactProfile, it.showContactCardPicker) },
            restore = {
                ChatDetailContactSheetState(
                    showContactActions = it[0] as Boolean,
                    showContactProfile = it[1] as Boolean,
                    showContactCardPicker = it[2] as Boolean,
                )
            },
        )
    }
}

/** 与原来的逐字段 `rememberSaveable` 等价。 */
@Composable
internal fun rememberChatDetailContactSheetState(): ChatDetailContactSheetState =
    rememberSaveable(saver = ChatDetailContactSheetState.Saver) { ChatDetailContactSheetState() }
