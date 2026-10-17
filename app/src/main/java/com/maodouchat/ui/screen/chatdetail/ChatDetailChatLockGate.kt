package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R


/** 聊天锁门控分支（从 ChatDetailRoute.kt 纯搬移，块字节级一致）。 */
@Composable
internal fun ChatDetailChatLockGate(
    state: ChatDetailUiState,
    chatLock: ChatDetailChatLockState,
    viewModel: ChatDetailViewModel,
) {
    ChatLockGate(
        chatName = state.contact.displayName.ifBlank {
            state.chat?.groupName.orEmpty().ifBlank { stringResource(R.string.chat_this_chat) }
        },
        onUnlock = { pin, onResult -> viewModel.unlockChatWithPin(pin, onResult) },
        onForgotPin = { chatLock.showForgotChatLockConfirm = true }
    )
    ForgotChatLockConfirmDialog(
        visible = chatLock.showForgotChatLockConfirm,
        onDismiss = { chatLock.showForgotChatLockConfirm = false },
        onConfirm = {
            chatLock.showForgotChatLockConfirm = false
            viewModel.forgotChatLockAndClearLocal()
        },
    )
}
