package com.maodouchat.ui.screen.chatlist

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// SimpleDateFormat 非线程安全，ThreadLocal 每线程复用一个；只调 format，不残留状态。
private val chatTimeFormat: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.getDefault()) }

private val chatWeekdayFormat: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial { SimpleDateFormat("EEE", Locale.getDefault()) }

private val chatDateFormat: ThreadLocal<SimpleDateFormat> =
    ThreadLocal.withInitial { SimpleDateFormat("MM/dd", Locale.getDefault()) }

internal fun formatChatTime(ts: Long): String {
    if (ts <= 0L) return ""
    val cal = Calendar.getInstance()
    val msg = Calendar.getInstance().apply { timeInMillis = ts }
    return if (cal.get(Calendar.YEAR) == msg.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)) {
        chatTimeFormat.get().format(Date(ts))
    } else {
        // 9.278：TG 式时间分段——近一周内显示星期缩写（如「周一」/Mon），更早才显示日期
        val dayDiff = daysBetween(msg, cal)
        if (dayDiff in 1..6) {
            chatWeekdayFormat.get().format(Date(ts))
        } else {
            chatDateFormat.get().format(Date(ts))
        }
    }
}

/** 两个日历之间的自然日差（按零点对齐）。 */
internal fun daysBetween(earlier: Calendar, later: Calendar): Int {
    val e = Calendar.getInstance().apply { timeInMillis = earlier.timeInMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val l = Calendar.getInstance().apply { timeInMillis = later.timeInMillis; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return ((l.timeInMillis - e.timeInMillis) / 86400000L).toInt()
}
