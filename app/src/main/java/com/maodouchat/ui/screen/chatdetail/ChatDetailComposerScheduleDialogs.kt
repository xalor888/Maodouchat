package com.maodouchat.ui.screen.chatdetail

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.maodouchat.R

/**
 * 日历跳转对话框：选日期后跳到该日第一条消息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateJumpDialog(
    onDismiss: () -> Unit,
    onJump: (dayStartMillis: Long) -> Unit
) {
    val context = LocalContext.current
    val today = java.time.LocalDate.now()
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        }
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_jump_date_title)) },
        text = {
            DatePicker(state = datePickerState, showModeToggle = false)
        },
        confirmButton = {
            TextButton(
                enabled = datePickerState.selectedDateMillis != null,
                onClick = {
                    val utcMillis = datePickerState.selectedDateMillis ?: return@TextButton
                    // 选中的是 UTC 当天 00:00；换算成本地时区当天 00:00
                    val localStart = java.time.Instant.ofEpochMilli(utcMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    onJump(localStart)
                }
            ) { Text(stringResource(R.string.chat_jump_date_jump)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_later)) }
        }
    )
}

/**
 * System date → time picker; enforces [ScheduledMessagePolicy] min/max window before [onPicked].
 */
internal fun openScheduleDateTimePicker(
    context: Context,
    onPicked: (Long) -> Unit,
    onTooSoon: () -> Unit,
    onTooLate: () -> Unit
) {
    val now = java.util.Calendar.getInstance()
    val minCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis + com.maodouchat.util.ScheduledMessagePolicy.MIN_DELAY_MS
    }
    val maxCal = java.util.Calendar.getInstance().apply {
        timeInMillis = now.timeInMillis + com.maodouchat.util.ScheduledMessagePolicy.MAX_DELAY_MS
    }
    android.app.DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            android.app.TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    val picked = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.YEAR, year)
                        set(java.util.Calendar.MONTH, month)
                        set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                        set(java.util.Calendar.HOUR_OF_DAY, hourOfDay)
                        set(java.util.Calendar.MINUTE, minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    when {
                        picked < minCal.timeInMillis -> onTooSoon()
                        picked > maxCal.timeInMillis -> onTooLate()
                        else -> onPicked(picked)
                    }
                },
                minCal.get(java.util.Calendar.HOUR_OF_DAY),
                minCal.get(java.util.Calendar.MINUTE),
                true
            ).show()
        },
        minCal.get(java.util.Calendar.YEAR),
        minCal.get(java.util.Calendar.MONTH),
        minCal.get(java.util.Calendar.DAY_OF_MONTH)
    ).apply {
        datePicker.minDate = minCal.timeInMillis
        datePicker.maxDate = maxCal.timeInMillis
    }.show()
}
