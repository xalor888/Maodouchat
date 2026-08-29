package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

/** Public navigation entry point for a conversation detail surface. */
@Composable
fun ChatDetailScreen(
    onBack: () -> Unit = {},
    onVoiceCall: (contactId: String, contactName: String) -> Unit = { _, _ -> },
    onVideoCall: (contactId: String, contactName: String) -> Unit = { _, _ -> },
    onOpenSecretChat: (chatId: String) -> Unit = {},
    onOpenGroupDetail: (chatId: String) -> Unit = {},
    onOpenStarredMessages: (chatId: String) -> Unit = {},
    onOpenMediaCenter: (chatId: String) -> Unit = {},
    onOpenAiTasks: (chatId: String) -> Unit = {},
    onOpenGroupPoll: (chatId: String) -> Unit = {},
    onOpenGroupCheckin: (chatId: String) -> Unit = {},
    onOpenGroupChain: (chatId: String) -> Unit = {},
    onOpenGroupPk: (chatId: String) -> Unit = {},
    onOpenProfile: ((userId: String) -> Unit)? = null,
    onOpenCallHistory: (() -> Unit)? = null,
    viewModel: ChatDetailViewModel = viewModel(),
) {
    ChatDetailRoute(
        onBack = onBack,
        onVoiceCall = onVoiceCall,
        onVideoCall = onVideoCall,
        onOpenSecretChat = onOpenSecretChat,
        onOpenGroupDetail = onOpenGroupDetail,
        onOpenStarredMessages = onOpenStarredMessages,
        onOpenMediaCenter = onOpenMediaCenter,
        onOpenAiTasks = onOpenAiTasks,
        onOpenGroupPoll = onOpenGroupPoll,
        onOpenGroupCheckin = onOpenGroupCheckin,
        onOpenGroupChain = onOpenGroupChain,
        onOpenGroupPk = onOpenGroupPk,
        onOpenProfile = onOpenProfile,
        onOpenCallHistory = onOpenCallHistory,
        viewModel = viewModel,
    )
}
