package com.maodouchat.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomStatusLabelTest {

    @Test
    fun `every preset maps to its kind`() {
        val known = mapOf(
            "在线" to CustomStatusLabelKind.Online,
            "忙碌" to CustomStatusLabelKind.Busy,
            "开会中" to CustomStatusLabelKind.Meeting,
            "请勿打扰" to CustomStatusLabelKind.Dnd,
            "马上回来" to CustomStatusLabelKind.Brb,
            "休假中" to CustomStatusLabelKind.Vacation,
            "学习中" to CustomStatusLabelKind.Studying,
            "通勤中" to CustomStatusLabelKind.Commuting,
            "专注中" to CustomStatusLabelKind.Focusing,
            "吃饭中" to CustomStatusLabelKind.Eating,
            "旅游中" to CustomStatusLabelKind.Traveling,
            "运动中" to CustomStatusLabelKind.Exercising,
            "工作中" to CustomStatusLabelKind.Working,
            "通话中" to CustomStatusLabelKind.OnCall,
            "开车中" to CustomStatusLabelKind.Driving,
            "游戏中" to CustomStatusLabelKind.Gaming,
            "睡觉中" to CustomStatusLabelKind.Sleeping,
            "写作中" to CustomStatusLabelKind.Writing,
            "出差中" to CustomStatusLabelKind.BusinessTrip,
            "充电中" to CustomStatusLabelKind.Charging,
            "听歌中" to CustomStatusLabelKind.Listening,
            "阅读中" to CustomStatusLabelKind.Reading,
            "观影中" to CustomStatusLabelKind.Watching,
            "做饭中" to CustomStatusLabelKind.Cooking,
        )
        assertEquals(24, known.size)
        known.forEach { (wire, expected) ->
            assertEquals(expected, customStatusLabelKind(wire))
        }
    }

    @Test
    fun `unknown wire is raw`() {
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind("隐身"))
        assertEquals(CustomStatusLabelKind.Raw, customStatusLabelKind(""))
    }
}
