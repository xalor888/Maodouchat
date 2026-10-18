package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable

/** 门禁路由：聊天锁加载中 / 新设备风控锁 / 聊天锁拦截三路门禁，任一生效时展示对应占位，否则渲染主内容。 */
@Composable
internal fun ChatDetailGateScreen(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    gate: ChatDetailGateDerived,
    secretGate: ChatDetailSecretGateState,
    chatLock: ChatDetailChatLockState,
    content: @Composable () -> Unit,
) {
    if (gate.chatLockPending) {
        // 聊天锁加载中占位抽到同包 ChatDetailLockPendingIndicator，纯搬移不改判断。
        ChatDetailLockPendingIndicator()
    } else if (secretGate.deviceRiskLocked) {
        SecretNewDeviceRiskLocked(onRegisterClick = { secretGate.showDeviceRiskDialog = true })
    } else if (gate.chatLockBlocking) {
        ChatDetailChatLockGate(
            state = state,
            chatLock = chatLock,
            viewModel = viewModel,
        )
    } else {
        content()
    }
}
