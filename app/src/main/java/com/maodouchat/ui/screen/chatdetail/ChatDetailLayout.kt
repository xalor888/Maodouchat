package com.maodouchat.ui.screen.chatdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Shell for the chat's existing top bar, snackbar host, and padded content. */
@Composable
internal fun ChatDetailScaffold(
    snackbarHost: @Composable () -> Unit,
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = snackbarHost,
        topBar = topBar,
        content = content,
    )
}

/** Fixed timeline viewport that preserves the overlay coordinate space used by chat controls. */
@Composable
internal fun ChatTimelinePane(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier,
        content = content,
    )
}
