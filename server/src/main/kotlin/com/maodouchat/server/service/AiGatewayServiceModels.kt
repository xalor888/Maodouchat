package com.maodouchat.server.service

internal data class Reservation(val amount: Long, val expiresAt: Long)
internal class BudgetMonitor(val lock: Any = Any(), var users: Int = 0)
