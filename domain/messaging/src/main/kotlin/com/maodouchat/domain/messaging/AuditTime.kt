package com.maodouchat.domain.messaging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 审计/审核时间戳 → "M/d HH:mm"；与 ChatDateDisplay 同款 ThreadLocal 按 locale 分键缓存。
private val auditTimeFormatCache = ThreadLocal.withInitial { mutableMapOf<Locale, SimpleDateFormat>() }

fun formatAuditTime(timestamp: Long, locale: Locale = Locale.getDefault()): String =
    auditTimeFormatCache.get().getOrPut(locale) {
        SimpleDateFormat("M/d HH:mm", locale)
    }.format(Date(timestamp))
