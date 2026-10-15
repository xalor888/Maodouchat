package com.maodouchat.server.service

data class Lockout(var fails: Int, var lockUntil: Long, var lastFailureAt: Long = 0L)
