package com.maodouchat.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R
import com.maodouchat.domain.contacts.CustomStatusLabelKind
import com.maodouchat.domain.contacts.customStatusLabelKind

/**
 * 自定义状态预设（[com.maodouchat.domain.contacts.CustomStatusPolicy.PRESETS]）的本地化展示。
 * wire 值是多端同步的中文原文（见 CustomStatusPolicy 注释），任何展示方都不得直接
 * 输出 wire 值——此前联系人搜索结果直接显示「在线」，英文界面里混出中文就是这个原因。
 */
@Composable
fun localizedCustomStatusLabel(wire: String): String = when (customStatusLabelKind(wire)) {
    CustomStatusLabelKind.Online -> stringResource(R.string.status_preset_online)
    CustomStatusLabelKind.Busy -> stringResource(R.string.status_preset_busy)
    CustomStatusLabelKind.Meeting -> stringResource(R.string.status_preset_meeting)
    CustomStatusLabelKind.Dnd -> stringResource(R.string.status_preset_dnd)
    CustomStatusLabelKind.Brb -> stringResource(R.string.status_preset_brb)
    CustomStatusLabelKind.Vacation -> stringResource(R.string.status_preset_vacation)
    CustomStatusLabelKind.Studying -> stringResource(R.string.status_preset_studying)
    CustomStatusLabelKind.Commuting -> stringResource(R.string.status_preset_commuting)
    CustomStatusLabelKind.Focusing -> stringResource(R.string.status_preset_focusing)
    CustomStatusLabelKind.Eating -> stringResource(R.string.status_preset_eating)
    CustomStatusLabelKind.Traveling -> stringResource(R.string.status_preset_traveling)
    CustomStatusLabelKind.Exercising -> stringResource(R.string.status_preset_exercising)
    CustomStatusLabelKind.Working -> stringResource(R.string.status_preset_working)
    CustomStatusLabelKind.OnCall -> stringResource(R.string.status_preset_on_call)
    CustomStatusLabelKind.Driving -> stringResource(R.string.status_preset_driving)
    CustomStatusLabelKind.Gaming -> stringResource(R.string.status_preset_gaming)
    CustomStatusLabelKind.Sleeping -> stringResource(R.string.status_preset_sleeping)
    CustomStatusLabelKind.Writing -> stringResource(R.string.status_preset_writing)
    CustomStatusLabelKind.BusinessTrip -> stringResource(R.string.status_preset_business_trip)
    CustomStatusLabelKind.Charging -> stringResource(R.string.status_preset_charging)
    CustomStatusLabelKind.Listening -> stringResource(R.string.status_preset_listening)
    CustomStatusLabelKind.Reading -> stringResource(R.string.status_preset_reading)
    CustomStatusLabelKind.Watching -> stringResource(R.string.status_preset_watching)
    CustomStatusLabelKind.Cooking -> stringResource(R.string.status_preset_cooking)
    CustomStatusLabelKind.Raw -> wire
}
