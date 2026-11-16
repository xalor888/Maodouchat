package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.maodouchat.core.model.Message
import com.maodouchat.domain.messaging.resolveMessageSelectionToolbar
import com.maodouchat.domain.messaging.selectableMessageIds
import com.maodouchat.util.RuntimeFlags

/**
 * 消息多选模式的工具条（G84 从 `ChatDetailRoute.kt` 拆出，原 79 行）。
 *
 * 六项派生判定在 :domain:messaging 的 `MessageSelectionToolbarPolicy`，此处只留接线：
 * - `forwardableMessages`：选中里可转发的（密聊 + 开关可禁转发）；
 * - `shouldStar`：存在未置星的就显示「置星」，否则「取消置星」；
 * - `shouldPin` / `canBatchPin`：置顶入口只在有权限且选中可置顶时出现（1.20）；
 * - `selectableIds`：**排除 SYSTEM/SK_DIST、在途 SENDING、附件上传中**（8.53，
 *   删了会与服务端 404 竞态，outbox flusher 仍可能把消息发出去）。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`；
 * 所需输入全部经参数显式传入，`Context` 只用 `LocalContext.current`。纯搬移，不改判断。
 *
 * @param visible 是否处于多选模式
 * @param selectedMessages 选中的消息
 * @param allMessages 当前列表全部消息（用于算 selectableIds）
 */
@Composable
internal fun ChatDetailSelectionToolbar(
    visible: Boolean,
    selectedMessages: List<Message>,
    allMessages: List<Message>,
    selectedIds: Set<String>,
    chatIsGroup: Boolean,
    myMemberRole: String?,
    pinnedMessageIds: Set<String>,
    isSecretChat: Boolean,
    preparingAttachmentMessageIds: Set<String>,
    onSelectAll: (Set<String>) -> Unit,
    onClearSelection: () -> Unit,
    onForward: (List<Message>) -> Unit,
    onToggleStar: (List<String>, Boolean) -> Unit,
    onDelete: () -> Unit,
    onTogglePin: (List<String>, Boolean) -> Unit,
    onCopied: (String) -> Unit,
    onCopyFailed: () -> Unit,
) {
    val context = LocalContext.current

        val toolbarState = resolveMessageSelectionToolbar(
            selectedMessages = selectedMessages,
            chatIsGroup = chatIsGroup,
            myMemberRole = myMemberRole,
            pinnedMessageIds = pinnedMessageIds,
            isSecretChat = isSecretChat,
            forwardBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_FORWARD_BLOCK),
            copyBlockEnabled = RuntimeFlags.isEnabled(context, RuntimeFlags.SECRET_COPY_BLOCK),
        )
        val forwardableMessages = toolbarState.forwardableMessages
        val shouldStar = toolbarState.shouldStar
        val shouldPin = toolbarState.shouldPin
        val canBatchPin = toolbarState.canBatchPin
        val selectableIds = remember(allMessages) {
            selectableMessageIds(allMessages, preparingAttachmentMessageIds)
        }
        ChatSelectionToolbar(
            selectedCount = selectedIds.size,
            canForward = forwardableMessages.isNotEmpty(),
            shouldStar = shouldStar,
            canSelectAll = selectableIds.isNotEmpty() && !selectedIds.containsAll(selectableIds),
            onSelectAll = { onSelectAll(selectableIds) },
            onClearSelection = { onClearSelection() },
            onCancel = { onClearSelection() },
            onForward = { onForward(forwardableMessages) },
            onToggleStar = {
                // 9.227：改串行批量，避免逐条并发触发 toggleStarMessage 扇出 N 个 REST
                onToggleStar(selectedMessages.map { it.id }, shouldStar)
                onClearSelection()
            },
            onDelete = { onDelete() },
            onCopy = if (isSecretChat) null else {
                {
                    val copyText = toolbarState.copyText
                    if (copyText != null) {
                        onCopied(copyText)
                        onClearSelection()
                    } else {
                        onCopyFailed()
                    }
                }
            },
            // 1.10：批量置顶/取消置顶选中消息（1.20：无权限时不显示入口）
            shouldPin = shouldPin,
            onTogglePin = if (canBatchPin) {
                {
                    onTogglePin(selectedMessages.map { it.id }, shouldPin)
                    onClearSelection()
                }
            } else null
        )}
