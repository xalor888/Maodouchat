package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeAccentPolicyTest {

    @Test
    fun nullReturnsNone() {
        assertEquals("none", ThemeAccentPolicy.normalizeThemeAccent(null))
    }

    @Test
    fun noneStaysNone() {
        assertEquals("none", ThemeAccentPolicy.normalizeThemeAccent("none"))
    }

    @Test
    fun knownColorIsCaseInsensitive() {
        assertEquals("blue", ThemeAccentPolicy.normalizeThemeAccent("BLUE"))
    }

    @Test
    fun whitespaceIsTrimmed() {
        assertEquals("teal", ThemeAccentPolicy.normalizeThemeAccent(" teal "))
    }

    @Test
    fun unknownReturnsNone() {
        assertEquals("none", ThemeAccentPolicy.normalizeThemeAccent("gold"))
    }
}
