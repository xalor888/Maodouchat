package com.maodouchat.push

sealed interface Decision {
    data object Accept : Decision
    data class Reject(val reason: String) : Decision
}
