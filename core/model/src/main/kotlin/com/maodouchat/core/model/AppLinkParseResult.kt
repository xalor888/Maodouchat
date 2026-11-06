package com.maodouchat.core.model

sealed interface AppLinkParseResult {
    data class Accepted(val destination: AppLinkDestination) : AppLinkParseResult
    data class Rejected(val reason: String) : AppLinkParseResult
}
