package com.maodouchat.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.maodouchat.R

/**
 * 自定义状态预设（[com.maodouchat.util.CustomStatusPolicy.PRESETS]）的本地化展示。
 * wire 值是多端同步的中文原文（见 CustomStatusPolicy 注释），任何展示方都不得直接
 * 输出 wire 值——此前联系人搜索结果直接显示「在线」，英文界面里混出中文就是这个原因。
 */
@Composable
fun localizedCustomStatusLabel(wire: String): String = when (wire) {
    "在线" -> stringResource(R.string.status_preset_online)
    "忙碌" -> stringResource(R.string.status_preset_busy)
    "开会中" -> stringResource(R.string.status_preset_meeting)
    "请勿打扰" -> stringResource(R.string.status_preset_dnd)
    "马上回来" -> stringResource(R.string.status_preset_brb)
    "休假中" -> stringResource(R.string.status_preset_vacation)
    "学习中" -> stringResource(R.string.status_preset_studying)
    "通勤中" -> stringResource(R.string.status_preset_commuting)
    "专注中" -> stringResource(R.string.status_preset_focusing)
    "吃饭中" -> stringResource(R.string.status_preset_eating)
    "旅游中" -> stringResource(R.string.status_preset_traveling)
    "运动中" -> stringResource(R.string.status_preset_exercising)
    "工作中" -> stringResource(R.string.status_preset_working)
    "通话中" -> stringResource(R.string.status_preset_on_call)
    "开车中" -> stringResource(R.string.status_preset_driving)
    "游戏中" -> stringResource(R.string.status_preset_gaming)
    "睡觉中" -> stringResource(R.string.status_preset_sleeping)
    "写作中" -> stringResource(R.string.status_preset_writing)
    "出差中" -> stringResource(R.string.status_preset_business_trip)
    "充电中" -> stringResource(R.string.status_preset_charging)
    "听歌中" -> stringResource(R.string.status_preset_listening)
    "阅读中" -> stringResource(R.string.status_preset_reading)
    "观影中" -> stringResource(R.string.status_preset_watching)
    "做饭中" -> stringResource(R.string.status_preset_cooking)
    else -> wire
}
