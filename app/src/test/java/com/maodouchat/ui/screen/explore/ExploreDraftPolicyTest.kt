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

    @Test
    fun scopedKeyRejectsWhitespaceOnlyOwner() {
        // 全空白 owner 也不是合法账号：不能落 key
        assertNull(ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "   "))
        assertNull(ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_VISIBILITY, "\t"))
    }

    @Test
    fun keyConstantsPinnedForStoredDrafts() {
        // key 命名钉住：改名会让已存草稿读不回来
        assertEquals("composer_text", ExploreDraftPolicy.KEY_COMPOSER_TEXT)
        assertEquals("selected_visibility", ExploreDraftPolicy.KEY_VISIBILITY)
    }

    @Test
    fun scopedKeysStableAcrossAccountSwitch() {
        val before = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "user-a")
        ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "user-b")
        val after = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "user-a")
        assertEquals(before, after)
    }

    @Test
    fun normalizeVisibilityDoesNotTrim() {
        // 前后带空格不认，直接回落
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility(" PUBLIC "))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("CONTACTS "))
    }

    @Test
    fun normalizeVisibilityAcceptsOnlyExactCase() {
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("Public"))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("private"))
    }

    @Test
    fun scopedKeyKeepsEmptyBaseVerbatim() {
        // 空 base 不特殊处理：分隔符照样拼
        assertEquals(":user-a", ExploreDraftPolicy.scopedKey("", "user-a"))
    }

    @Test
    fun scopedKeyKeepsColonInOwnerVerbatim() {
        // owner 里的冒号不转义不拆分
        assertEquals(
            "composer_text:a:b",
            ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "a:b")
        )
    }

    @Test
    fun scopedKeyTreatsNonBreakingSpaceAsBlank() {
        // Kotlin 的 isBlank 把 U+00A0 判空白：不断言合理，只钉住当前行为
        assertNull(ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "\u00A0"))
    }

    @Test
    fun scopedKeyKeepsBaseVerbatim() {
        // base 也不裁边，与 owner 对称
        assertEquals(
            " composer_text :u",
            ExploreDraftPolicy.scopedKey(" composer_text ", "u")
        )
    }

    @Test
    fun normalizeVisibilityRejectsFullwidthVariant() {
        // 全角变体不是集合成员，直接回落
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("ＰＵＢＬＩＣ"))
    }
}
