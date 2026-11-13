package com.maodouchat.domain.messaging

import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// 日期展示纯决策：今天/昨天/更早三分桶与日期时间格式化；文案由 app 侧按结果映射到 R.string。
sealed interface ChatDateLabel {
    data object Today : ChatDateLabel
    data object Yesterday : ChatDateLabel
    data object Date : ChatDateLabel
}

object ChatDateDisplay {

    fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    fun isYesterday(today: Calendar, other: Calendar): Boolean {
        val y = today.clone() as Calendar
        y.add(Calendar.DAY_OF_YEAR, -1)
        return isSameDay(y, other)
    }

    // 今天/昨天/更早三分桶：原 formatDateLabel 的 when 逻辑逐字平移，文案层留在 app 侧。
    fun dateLabelBucket(timestamp: Long, nowMillis: Long = System.currentTimeMillis()): ChatDateLabel {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return when {
            isSameDay(now, cal) -> ChatDateLabel.Today
            isYesterday(now, cal) -> ChatDateLabel.Yesterday
            else -> ChatDateLabel.Date
        }
    }

    // DateFormat 非线程安全：ThreadLocal 按 locale 分键缓存，语言切换后自动重建。
    private val dateTimeFormatCache = ThreadLocal.withInitial { mutableMapOf<Locale, DateFormat>() }

    fun formatChatDateTime(timestamp: Long, locale: Locale): String =
        dateTimeFormatCache.get().getOrPut(locale) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        }.format(Date(timestamp))
}
