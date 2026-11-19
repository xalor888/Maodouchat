package com.maodouchat.domain.messaging

import kotlinx.serialization.Serializable

@Serializable
data class AiContextMessage(val sender: String = "", val text: String)
