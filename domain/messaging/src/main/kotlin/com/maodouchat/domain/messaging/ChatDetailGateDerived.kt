package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ChatDetailUiState

// 门禁派生量族：聊天锁门禁两态（加载中/拦截中）+ 密聊/阅后即焚活跃态。
// 原在 ChatDetailRoute 内联计算，纯搬移不改判断。
class ChatDetailGateDerived(
    val chatLockPending: Boolean,
    val chatLockBlocking: Boolean,
    val disappearingActive: Boolean,
    val secretActive: Boolean,
)

/**
 * 门禁派生量：四个都是 state 的纯函数，重算代价忽略不计（原为内联计算），
 * 所以调用方不做 remember，接线函数只负责把它们收进一个持有类。
 */
fun resolveChatDetailGateDerived(state: ChatDetailUiState): ChatDetailGateDerived =
    ChatDetailGateDerived(
        chatLockPending = state.isChatLocked == null,
        chatLockBlocking = state.isChatLocked == true && !state.isChatUnlocked,
        disappearingActive = !state.chatIsGroup && state.disappearingMessageSeconds > 0,
        secretActive = state.isSecretChat == true,
    )
