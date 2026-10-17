package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** 聊天锁加载中占位（从 ChatDetailRoute.kt 纯搬移，块字节级一致）。 */
@Composable
internal fun ChatDetailLockPendingIndicator() {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
