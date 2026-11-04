package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message

internal data class Input(
    val messages: List<Message>,
    val hasChat: Boolean,
    val isActiveChat: Boolean,
    val ownerUserId: String,
    val sessionMayContinue: Boolean,
)
