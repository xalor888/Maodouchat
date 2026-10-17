package com.maodouchat.ui.screen.chatdetail

import android.content.Context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

// 从 ChatDetailRoute.kt 纯搬移：副作用簇抽出，块字节级一致。
@Composable
internal fun ChatDetailSecretChatEffects(
    state: ChatDetailUiState,
    viewModel: ChatDetailViewModel,
    context: Context,
    onOpenSecretChat: (chatId: String) -> Unit,
) {
    val secretActive = state.isSecretChat == true
    LaunchedEffect(state.openedSecretChatId) {
        val secretId = state.openedSecretChatId ?: return@LaunchedEffect
        viewModel.clearOpenedSecretChat()
        onOpenSecretChat(secretId)
    }

    // B2 密聊 TTL（ttlz）：会话活跃心跳——进入与驻留期间持续更新 lastActivityAt，避免无活动误销毁。
    // G9：逻辑已抽到 SecretChatActivityHeartbeat（非 Composable，可单测），这里只负责按会话触发。
    LaunchedEffect(secretActive, state.chat?.id) {
        if (!secretActive) return@LaunchedEffect
        val chatId = state.chat?.id ?: return@LaunchedEffect
        com.maodouchat.security.SecretChatActivityHeartbeat.start(context, chatId)
    }

    LaunchedEffect(secretActive, state.chat?.id) {
        if (!secretActive) return@LaunchedEffect
        while (true) {
            viewModel.refreshSealedSenderCertificate()
            kotlinx.coroutines.delay(10 * 60 * 1000L)
        }
    }
}
