package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ChatDetailSnackbarHost(chatSnackbarHostState: SnackbarHostState) {
    SnackbarHost(
        hostState = chatSnackbarHostState,
        modifier = Modifier
            .navigationBarsPadding()
            .padding(bottom = 80.dp)
    )
}
