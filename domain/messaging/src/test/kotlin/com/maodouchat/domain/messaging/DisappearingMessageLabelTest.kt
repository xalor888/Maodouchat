package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class DisappearingMessageLabelTest {

    @Test
    fun allKnownDurationsMapToTheirLabels() {
        val cases = listOf(
            0 to DisappearDurationLabel.Off,
            30 to DisappearDurationLabel.Seconds30,
            60 to DisappearDurationLabel.Minute1,
            2 * 60 to DisappearDurationLabel.Minute2,
            5 * 60 to DisappearDurationLabel.Minute5,
            15 * 60 to DisappearDurationLabel.Minute15,
            60 * 60 to DisappearDurationLabel.Hour1,
            2 * 60 * 60 to DisappearDurationLabel.Hour2,
            4 * 60 * 60 to DisappearDurationLabel.Hour4,
            8 * 60 * 60 to DisappearDurationLabel.Hour8,
            12 * 60 * 60 to DisappearDurationLabel.Hour12,
            24 * 60 * 60 to DisappearDurationLabel.Hour24,
            7 * 24 * 60 * 60 to DisappearDurationLabel.Days7,
            30 * 24 * 60 * 60 to DisappearDurationLabel.Days30,
        )
        for ((seconds, expected) in cases) {
            assertEquals(
                expected,
                DisappearingMessageLabel.disappearDurationLabel(seconds),
                "seconds=$seconds",
            )
        }
    }

    @Test
    fun unknownSecondsFallBackToOff() {
        for (seconds in listOf(1, 45, 90, -5, 31 * 24 * 60 * 60)) {
            assertEquals(
                DisappearDurationLabel.Off,
                DisappearingMessageLabel.disappearDurationLabel(seconds),
                "seconds=$seconds",
            )
        }
    }

    @Test
    fun allFourteenLabelsAreReachable() {
        assertEquals(14, DisappearDurationLabel.entries.size)
    }
}
