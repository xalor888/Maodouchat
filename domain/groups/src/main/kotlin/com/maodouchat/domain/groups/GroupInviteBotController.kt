package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupDetailUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 群详情邀请自有机器人时向 UI 写入的文案种类。domain 侧只定种类，真正的本地化字符串由 app 侧映射提供——domain 模块不引用 app 的 R。 */
enum class GroupInviteBotMessage {
    INVITED,
    INVITE_FAILED,
}

// 群详情邀请自有机器人，由 GroupDetailViewModel.inviteOwnedBot 纯搬移：
// 空 id/进行中守卫、两次会话门禁、成功重载并带提示、失败写提示、取消时复位标记并重抛。
// 网络调用走 inviteBot 端口（其内部已切 Dispatchers.IO，原外层 withContext 等价去掉）。
class GroupInviteBotController(
    private val scope: CoroutineScope,
    private val currentState: () -> GroupDetailUiState,
    private val updateState: ((GroupDetailUiState) -> GroupDetailUiState) -> Unit,
    private val chatId: () -> String,
    private val ownerUserId: () -> String,
    private val sessionActive: (String) -> Boolean,
    private val inviteBot: suspend (chatId: String, botId: String) -> Result<String>,
    private val text: (GroupInviteBotMessage) -> String,
    private val reload: (String?) -> Unit,
) {
    fun inviteOwnedBot(botId: String) {
        if (botId.isBlank() || chatId().isBlank() || currentState().isInvitingBot) return
        val owner = ownerUserId()
        scope.launch {
            updateState { it.copy(isInvitingBot = true, message = null) }
            try {
                if (!sessionActive(owner)) return@launch
                val result = inviteBot(chatId(), botId)
                if (!sessionActive(owner)) return@launch
                result.fold(
                    onSuccess = {
                        updateState { it.copy(isInvitingBot = false) }
                        reload(text(GroupInviteBotMessage.INVITED))
                    },
                    onFailure = {
                        updateState {
                            it.copy(
                                isInvitingBot = false,
                                message = text(GroupInviteBotMessage.INVITE_FAILED)
                            )
                        }
                    }
                )
            } catch (error: CancellationException) {
                updateState { it.copy(isInvitingBot = false) }
                throw error
            }
        }
    }
}
