package com.maodouchat.server.service

data class Cursor(val epoch: Long, val sequence: Long)

sealed interface Admit {
}
