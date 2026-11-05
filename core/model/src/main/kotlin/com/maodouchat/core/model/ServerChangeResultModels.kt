package com.maodouchat.core.model

sealed interface ServerChangeResult {
    data object Changed : ServerChangeResult
    data object Unchanged : ServerChangeResult
    data class Failed(val message: String) : ServerChangeResult
}
