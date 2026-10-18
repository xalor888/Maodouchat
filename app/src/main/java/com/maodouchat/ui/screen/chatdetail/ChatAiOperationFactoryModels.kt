package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.local.entity.AiOperationParameters

internal data class Descriptor(
    val type: String,
    val targetMessageId: String?,
    val parameters: AiOperationParameters
)
