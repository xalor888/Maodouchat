package com.maodouchat.domain.messaging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// AI 任务到期时间 → "yyyy-MM-dd HH:mm"；与 ChatDateDisplay 同款 ThreadLocal 按 locale 分键缓存。
private val aiTaskDueFormatCache = ThreadLocal.withInitial { mutableMapOf<Locale, SimpleDateFormat>() }

fun formatAiTaskDue(timestamp: Long, locale: Locale = Locale.getDefault()): String =
    aiTaskDueFormatCache.get().getOrPut(locale) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
    }.format(Date(timestamp))

// 任务行截止标签：自带 dueText 与格式化时间去空、去重后用" · "连接，全空时不渲染。
fun aiTaskDueLabel(dueText: String?, formattedDueAt: String?): String? =
    listOfNotNull(dueText?.takeIf(String::isNotBlank), formattedDueAt)
        .distinct()
        .joinToString(" · ")
        .takeIf(String::isNotBlank)

// 任务是否逾期：未完成、有截止时间且已过期；颜色映射留在 app 侧。
fun isAiTaskOverdue(isCompleted: Boolean, dueAt: Long?, nowMillis: Long): Boolean =
    !isCompleted && dueAt != null && dueAt < nowMillis
