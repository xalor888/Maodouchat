package com.maodouchat.ui.screen.chatdetail

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun GroupEditScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel()
) {
    com.maodouchat.ui.screen.chatdetail.group.GroupEditScreen(onBack = onBack, viewModel = viewModel)
}

@Composable
fun GroupInviteQrScreen(
    onBack: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel()
) {
    com.maodouchat.ui.screen.chatdetail.group.GroupInviteQrScreen(onBack = onBack, viewModel = viewModel)
}
