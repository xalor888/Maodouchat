package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AccentColorKindTest {

    @Test
    fun blueMapsToBlue() {
        assertEquals(AccentColorKind.BLUE, accentColorKind("blue"))
    }

    @Test
    fun greenMapsToGreen() {
        assertEquals(AccentColorKind.GREEN, accentColorKind("green"))
    }

    @Test
    fun purpleMapsToPurple() {
        assertEquals(AccentColorKind.PURPLE, accentColorKind("purple"))
    }

    @Test
    fun orangeMapsToOrange() {
        assertEquals(AccentColorKind.ORANGE, accentColorKind("orange"))
    }

    @Test
    fun pinkMapsToPink() {
        assertEquals(AccentColorKind.PINK, accentColorKind("pink"))
    }

    @Test
    fun redMapsToRed() {
        assertEquals(AccentColorKind.RED, accentColorKind("red"))
    }

    @Test
    fun tealIdMapsToTeal() {
        assertEquals(AccentColorKind.TEAL, accentColorKind("teal"))
    }

    @Test
    fun unknownAndEmptyFallBackToTeal() {
        assertEquals(AccentColorKind.TEAL, accentColorKind("none"))
        assertEquals(AccentColorKind.TEAL, accentColorKind(""))
    }
}
