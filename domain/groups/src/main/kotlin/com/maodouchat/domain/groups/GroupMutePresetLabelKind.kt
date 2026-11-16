package com.maodouchat.domain.groups

import com.maodouchat.core.model.GroupMutePreset

// 群禁言预设标签种类；预设枚举本身在 :core:model，domain 只做 1:1 映射。
enum class GroupMutePresetLabelKind {
    MINUTES_5,
    MINUTES_10,
    MINUTES_30,
    HOUR_1,
    HOURS_2,
    HOURS_3,
    HOURS_6,
    HOURS_8,
    DAY_1,
    DAYS_7,
    DAYS_30,
}

fun groupMutePresetLabelKind(preset: GroupMutePreset): GroupMutePresetLabelKind =
    when (preset) {
        GroupMutePreset.MINUTES_5 -> GroupMutePresetLabelKind.MINUTES_5
        GroupMutePreset.MINUTES_10 -> GroupMutePresetLabelKind.MINUTES_10
        GroupMutePreset.MINUTES_30 -> GroupMutePresetLabelKind.MINUTES_30
        GroupMutePreset.HOUR_1 -> GroupMutePresetLabelKind.HOUR_1
        GroupMutePreset.HOURS_2 -> GroupMutePresetLabelKind.HOURS_2
        GroupMutePreset.HOURS_3 -> GroupMutePresetLabelKind.HOURS_3
        GroupMutePreset.HOURS_6 -> GroupMutePresetLabelKind.HOURS_6
        GroupMutePreset.HOURS_8 -> GroupMutePresetLabelKind.HOURS_8
        GroupMutePreset.DAY_1 -> GroupMutePresetLabelKind.DAY_1
        GroupMutePreset.DAYS_7 -> GroupMutePresetLabelKind.DAYS_7
        GroupMutePreset.DAYS_30 -> GroupMutePresetLabelKind.DAYS_30
    }
