package com.maodouchat.domain.conversation

sealed interface PushWakeDecision {
    data object Accept : PushWakeDecision
    data class Reject(val reason: String) : PushWakeDecision
}
