package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeWorkbenchWidgetKindTest {

    @Test
    fun bubbleWidget() {
        assertEquals(ThemeWorkbenchWidgetKind.BUBBLE, themeWorkbenchWidgetKind("bubble"))
    }

    @Test
    fun profileWidget() {
        assertEquals(ThemeWorkbenchWidgetKind.PROFILE, themeWorkbenchWidgetKind("profile"))
    }

    @Test
    fun actionsWidget() {
        assertEquals(ThemeWorkbenchWidgetKind.ACTIONS, themeWorkbenchWidgetKind("actions"))
    }

    @Test
    fun statsWidget() {
        assertEquals(ThemeWorkbenchWidgetKind.STATS, themeWorkbenchWidgetKind("stats"))
    }

    @Test
    fun unknownWidgetFallsBackToCustom() {
        assertEquals(ThemeWorkbenchWidgetKind.CUSTOM, themeWorkbenchWidgetKind("custom_x"))
        assertEquals(ThemeWorkbenchWidgetKind.CUSTOM, themeWorkbenchWidgetKind(""))
    }
}
