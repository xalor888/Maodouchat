package com.maodouchat.domain.contacts

// 自定义状态 wire 值→文案种类；与 CustomStatusPolicy.PRESETS 同表，未列出的 wire 值原样回显。
enum class CustomStatusLabelKind {
    Online,
    Busy,
    Meeting,
    Dnd,
    Brb,
    Vacation,
    Studying,
    Commuting,
    Focusing,
    Eating,
    Traveling,
    Exercising,
    Working,
    OnCall,
    Driving,
    Gaming,
    Sleeping,
    Writing,
    BusinessTrip,
    Charging,
    Listening,
    Reading,
    Watching,
    Cooking,
    Raw,
}

fun customStatusLabelKind(wire: String): CustomStatusLabelKind = when (wire) {
    "在线" -> CustomStatusLabelKind.Online
    "忙碌" -> CustomStatusLabelKind.Busy
    "开会中" -> CustomStatusLabelKind.Meeting
    "请勿打扰" -> CustomStatusLabelKind.Dnd
    "马上回来" -> CustomStatusLabelKind.Brb
    "休假中" -> CustomStatusLabelKind.Vacation
    "学习中" -> CustomStatusLabelKind.Studying
    "通勤中" -> CustomStatusLabelKind.Commuting
    "专注中" -> CustomStatusLabelKind.Focusing
    "吃饭中" -> CustomStatusLabelKind.Eating
    "旅游中" -> CustomStatusLabelKind.Traveling
    "运动中" -> CustomStatusLabelKind.Exercising
    "工作中" -> CustomStatusLabelKind.Working
    "通话中" -> CustomStatusLabelKind.OnCall
    "开车中" -> CustomStatusLabelKind.Driving
    "游戏中" -> CustomStatusLabelKind.Gaming
    "睡觉中" -> CustomStatusLabelKind.Sleeping
    "写作中" -> CustomStatusLabelKind.Writing
    "出差中" -> CustomStatusLabelKind.BusinessTrip
    "充电中" -> CustomStatusLabelKind.Charging
    "听歌中" -> CustomStatusLabelKind.Listening
    "阅读中" -> CustomStatusLabelKind.Reading
    "观影中" -> CustomStatusLabelKind.Watching
    "做饭中" -> CustomStatusLabelKind.Cooking
    else -> CustomStatusLabelKind.Raw
}
