package com.maodouchat.ui.screen.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.maodouchat.explore.policy.ExploreDraftPolicy

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

    @Test
    fun normalizeVisibilityPassesKnownValuesThrough() {
        assertEquals("PUBLIC", ExploreDraftPolicy.normalizeVisibility("PUBLIC"))
        assertEquals("CONTACTS", ExploreDraftPolicy.normalizeVisibility("CONTACTS"))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("PRIVATE"))
    }

    @Test
    fun normalizeVisibilityFallsBackToPrivate() {
        // 未知值/空串/大小写变体全部回落 PRIVATE（与设置页的 PUBLIC 回落方向相反，G179 待决策）
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("FRIENDS"))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility(""))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("public"))
    }

    @Test
    fun scopedKeyKeepsOwnerVerbatim() {
        // owner 不裁边：不同原始 userId 永不撞 key
        assertEquals(
            "composer_text: user-a ",
            ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, " user-a ")
        )
    }

    @Test
    fun scopedKeyDiffersByBase() {
        val text = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "user-a")
        val visibility = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_VISIBILITY, "user-a")
        assertNotEquals(text, visibility)
    }

    @Test
    fun scopedKeyIsDeterministic() {
        val first = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "user-a")
        val second = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "user-a")
        assertEquals(first, second)
    }
}
