package com.maodouchat.ui.screen.chatdetail.group

import androidx.compose.ui.graphics.vector.ImageVector

data class GroupFeatureAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)
