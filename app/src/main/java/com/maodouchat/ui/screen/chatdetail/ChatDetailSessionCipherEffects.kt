package com.maodouchat.ui.screen.chatdetail

import android.content.Context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect

// 从 ChatDetailRoute.kt 纯搬移：副作用簇抽出，块字节级一致。
@Composable
internal fun ChatDetailSessionCipherEffects(
    viewModel: ChatDetailViewModel,
    state: ChatDetailUiState,
    aiSafety: ChatDetailAiSafetyState,
    appearance: ChatDetailAppearanceState,
    context: Context,
) {
    LaunchedEffect(viewModel.activeChatId, state.contact.id, state.chatIsGroup) {
        val peer = state.contact.id.takeIf { it.isNotBlank() && it != "me" && !state.chatIsGroup }
        when {
            state.chatIsGroup -> {
                viewModel.occupySessionCipher(
                    viewModel.activeChatId,
                    peerUserId = null,
                    updatePeer = true
                )
            }
            peer != null -> {
                viewModel.occupySessionCipher(
                    viewModel.activeChatId,
                    peer,
                    updatePeer = true
                )
            }
            else -> {
                // Contact not loaded yet — pin chatId only. Never pass updatePeer=true
                // with a blank peer: that clears openPeerUserId and lets list/backlog
                // decrypt the sibling DIRECT/SECRET ratchet.
                viewModel.occupySessionCipher(viewModel.activeChatId)
            }
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel.activeChatId, state.contact.id, state.chatIsGroup) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                aiSafety.refreshFrom(context)
                // 9.150：刷新外观偏好（设置页修改后返回即时生效）
                appearance.refreshFrom(context)
                // 8.32 修复 F2：回到前台恢复 activeChatId（MainActivity.onPause 已清空），
                // 使「打开中的聊天」重新享有消息不弹通知/不计未读的语义。
                val resumePeer = state.contact.id.takeIf { it.isNotBlank() && it != "me" && !state.chatIsGroup }
                when {
                    state.chatIsGroup -> viewModel.occupySessionCipher(
                        viewModel.activeChatId,
                        peerUserId = null,
                        updatePeer = true
                    )
                    resumePeer != null -> viewModel.occupySessionCipher(
                        viewModel.activeChatId,
                        resumePeer,
                        updatePeer = true
                    )
                    else -> viewModel.occupySessionCipher(viewModel.activeChatId)
                }
                // 8.32 修复 F2 的时间戳写入挪到 SessionCipherOccupancy（if-置值语义逐字等价）；Route 不再直连全局单例。
                com.maodouchat.crypto.SessionCipherOccupancy.refreshActiveChatOpenedAtIfUnset()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}
