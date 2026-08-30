package com.maodouchat.domain.messaging

/** 定时消息状态（M09）。 */
enum class ScheduleStatus { PENDING, SENT, CANCELLED, FAILED }

/** 重复周期类型（M09）。 */
enum class RecurrenceType { ONCE, DAILY, WEEKDAYS }

data class RecurrenceRule(
    val type: RecurrenceType,
    val intervalDays: Int = 1,
)

/** 定时消息（M09）：owner、状态、attempt、nextRunAt、幂等键。 */
data class ScheduledMessage(
    val id: String,
    val ownerUserId: String,
    val conversationId: String,
    val content: ContentPayload,
    val sendAtMillis: Long,
    val status: ScheduleStatus = ScheduleStatus.PENDING,
    val attempt: Int = 0,
    val recurrence: RecurrenceRule? = null,
    val idempotencyKey: String,
)

/**
 * 定时消息策略（纯逻辑）：是否到点、终态判定、重复周期下一次触发时间。
 * WEEKDAYS 的星期跳过在实现层用 java.time 处理，此处仅做等间隔推进。
 */
object ScheduledMessagePolicy {
    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

    val terminal = setOf(ScheduleStatus.SENT, ScheduleStatus.CANCELLED)

    fun isDue(schedule: ScheduledMessage, now: Long): Boolean =
        schedule.status == ScheduleStatus.PENDING && schedule.sendAtMillis <= now

    /** 重复调度下一次触发时间（> lastRunAt 且 >= now）；无重复或 ONCE 返回 null。 */
    fun nextRunAt(schedule: ScheduledMessage, lastRunAt: Long, now: Long): Long? {
        val rule = schedule.recurrence ?: return null
        if (rule.type == RecurrenceType.ONCE) return null
        val step = rule.intervalDays.coerceAtLeast(1) * DAY_MILLIS
        var candidate = lastRunAt + step
        while (candidate < now) candidate += step
        return candidate
    }
}

/** 定时消息存储（M09）：Room 实现，按 owner 隔离、幂等键去重。 */
interface ScheduledMessageStore {
    suspend fun upsert(schedule: ScheduledMessage): Result<Unit>
    suspend fun delete(id: String, ownerUserId: String): Result<Unit>
    suspend fun dueMessages(ownerUserId: String, now: Long): List<ScheduledMessage>
}
