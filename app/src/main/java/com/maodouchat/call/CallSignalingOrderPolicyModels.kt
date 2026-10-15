package com.maodouchat.call

data class Cursor(val epoch: Long, val sequence: Long)

sealed interface Admit {
    data object Accept : Admit
    data object RejectStale : Admit
}
