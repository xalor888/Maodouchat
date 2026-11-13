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
