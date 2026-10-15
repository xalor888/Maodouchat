package com.maodouchat.server.service

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal fun AiGatewayService.budgetMonitorFor(userId: String): BudgetMonitor {
    maybeSweepBudgetMonitors()
    return budgetMonitors.compute(userId) { _, existing ->
        val monitor = existing ?: BudgetMonitor()
        monitor.users++
        monitor
    }!!
}

internal fun AiGatewayService.releaseBudgetMonitor(userId: String, monitor: BudgetMonitor) {
    budgetMonitors.computeIfPresent(userId) { _, current ->
        if (current === monitor) {
            if (current.users > 1) {
                current.users--
                current
            } else if (budgetReservations[userId] == null) {
                null
            } else {
                current.users = 0
                current
            }
        } else {
            current
        }
    }
}

internal fun AiGatewayService.maybeSweepBudgetMonitors() {
    if (budgetMonitors.size < MAX_BUDGET_MONITORS) return
    val now = System.currentTimeMillis()
    val last = budgetMonitorSweepAt.get()
    if (now - last < 60_000L) return
    if (!budgetMonitorSweepAt.compareAndSet(last, now)) return
    budgetMonitors.keys.forEach { key ->
        budgetMonitors.computeIfPresent(key) { _, current ->
            if (current.users == 0 && budgetReservations[key] == null) null else current
        }
    }
}

internal fun AiGatewayService.purgeExpiredReservations(userId: String, now: Long): Long {
    val list = budgetReservations[userId] ?: return 0L
    val iter = list.iterator()
    var sum = 0L
    while (iter.hasNext()) {
        val r = iter.next()
        if (r.expiresAt <= now) iter.remove() else sum += r.amount
    }
    if (list.isEmpty()) budgetReservations.remove(userId)
    return sum
}

internal fun AiGatewayService.secondsUntilMidnight(zone: ZoneId = ZoneId.systemDefault()): Long {
    val now = Instant.now()
    val nextMidnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()
    return Duration.between(now, nextMidnight).seconds.coerceAtLeast(1L)
}
