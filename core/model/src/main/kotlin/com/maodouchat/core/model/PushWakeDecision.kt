package com.maodouchat.core.model

sealed interface PushWakeDecision {
    data object Accept : PushWakeDecision
    data class Reject(val reason: String) : PushWakeDecision
}
