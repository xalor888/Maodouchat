package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maodouchat.data.model.Message

/**
 * G335：`ChatDetailRoute` 里**「当前哪条消息正被某个弹层操作」**这一族状态的持有者。
 *
 * 为什么把它单拎出来（而不是继续留在 Route 里）：
 * 1. 这 14 个状态是**同一件事的不同侧面**——长按一条消息后，弹层的「目标消息」。
 *    它们原先散在 Route 里和别的 90 多个状态混在一起，读的人分不清「哪些是一组」；
 * 2. 它们全是 `remember { mutableStateOf<Message?>(null) }` 的同一形状，
 *    是 Route 里最容易一眼扫过去、却最常被读写的一族（实测 81 处引用）；
 * 3. 收进持有类后，Route 里只剩「谁设置它、谁消费它」的调用，状态形状不再占版面。
 *
 * **不放进来的**：`fileQuestionDraft` / `editDraft` 这类 `rememberSaveable` 的草稿——
 * 它们要跨进程重建存活，而普通持有类拿不到 `rememberSaveable` 的保存语义
 * （硬搬会静默失去旋转/重建后的恢复）。要搬得先给持有类写 Saver，属另一件事。
 *
 * 创建方式与 Route 里其他 `remember` 一致：`val messageActions = remember { ChatDetailMessageActionState() }`。
 * 字段用 `var` + `mutableStateOf`（而不是 `MutableState<T>` 对象），是为了让调用点的
 * `messageActions.messageToDelete = it` 读起来和原来的 `messageToDelete = it` 完全一样。
 */
internal class ChatDetailMessageActionState {
    /** 删除确认（含撤回失败回退到删除的路径）。 */
    var messageToDelete by mutableStateOf<Message?>(null)

    /** 撤回确认（带粒子动效）。 */
    var messageToRevoke by mutableStateOf<Message?>(null)

    /** 复制后提示。 */
    var messageToCopy by mutableStateOf<Message?>(null)

    /** 多选转发：选中的消息集合（空 = 未进入多选转发）。 */
    var messagesToForward by mutableStateOf<List<Message>>(emptyList())

    /** 重试发送确认。 */
    var messageToRetry by mutableStateOf<Message?>(null)

    /** 编辑消息（草稿内容在 Route 的 `editDraft`，那是 saveable）。 */
    var messageToEdit by mutableStateOf<Message?>(null)

    /** 长按消息的操作面板（`ChatDetailMessageActionsSheet`）。 */
    var messageToActions by mutableStateOf<Message?>(null)

    /** 「稍后提醒」时间选择。 */
    var messageToRemind by mutableStateOf<Message?>(null)

    /** 翻译语言选择。 */
    var messageToTranslate by mutableStateOf<Message?>(null)

    /** 举报消息。 */
    var messageToReport by mutableStateOf<Message?>(null)

    /** 已读回执面板（`ChatDetailReadReceiptsSheet`）。 */
    var messageForReadReceipts by mutableStateOf<Message?>(null)

    /** AI 图片分析模式选择。 */
    var messageToAnalyzeImage by mutableStateOf<Message?>(null)

    /** AI 文件分析模式选择。 */
    var messageToAnalyzeFile by mutableStateOf<Message?>(null)

    /** AI 文件问答（问题文本在 Route 的 `fileQuestionDraft`，那是 saveable）。 */
    var fileQuestionMessage by mutableStateOf<Message?>(null)
}
