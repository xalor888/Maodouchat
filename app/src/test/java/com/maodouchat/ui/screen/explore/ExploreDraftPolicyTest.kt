package com.maodouchat.ui.screen.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreDraftPolicyTest {
    @Test
    fun scopesKeysPerOwner() {
        val a = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "user-a")
        val b = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "user-b")
        assertEquals("composer_text:user-a", a)
        assertEquals("composer_text:user-b", b)
        assertNotEquals(a, b)
    }

    @Test
    fun blankOwnerYieldsNull() {
        assertNull(ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, ""))
        assertNull(ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_VISIBILITY, "   ".trim()))
    }
}
