package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.domain.messaging.ChatDetailGateDerived
import com.maodouchat.domain.messaging.resolveChatDetailGateDerived

// 门禁派生量族已迁入 :domain:messaging，这里只留接线。
@Composable
internal fun rememberChatDetailGateDerived(
    state: ChatDetailUiState,
): ChatDetailGateDerived = resolveChatDetailGateDerived(state)
