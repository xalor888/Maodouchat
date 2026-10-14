package com.maodouchat.settings.server

sealed interface ServerSwitchResult {
    data class Success(val oldUrl: String, val newUrl: String) : ServerSwitchResult
    data object Unchanged : ServerSwitchResult
    data class Failure(val reason: String, val rolledBack: Boolean) : ServerSwitchResult
}
