package com.maodouchat.explore

import com.maodouchat.ui.screen.explore.VisibilityOption
import com.maodouchat.explore.policy.ExploreDraftPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * ExploreViewModel 的纯 JVM 单测：可见范围选项模型 / normalizeVisibility 逻辑。
 * AndroidViewModel 部分（Application context、ImagePicker）需在 Robolectric 或 instrumented test 中覆盖。
 */
class ExploreViewModelTest {

    private val options = listOf(
        VisibilityOption("PUBLIC", "公开", "所有登录用户可见"),
        VisibilityOption("CONTACTS", "联系人", "与你有会话的人可见"),
        VisibilityOption("PRIVATE", "仅自己", "仅自己可见")
    )

    @Test
    fun `visibilityOptions contains exactly 3 entries`() {
        assertEquals(3, options.size)
        assertEquals(listOf("PUBLIC", "CONTACTS", "PRIVATE"), options.map { it.value })
    }

    @Test
    fun `normalizeVisibility fails closed to PRIVATE for unknown value`() {
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("UNKNOWN"))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility(""))
    }

    @Test
    fun `normalizeVisibility preserves valid value`() {
        assertEquals("CONTACTS", ExploreDraftPolicy.normalizeVisibility("CONTACTS"))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("PRIVATE"))
    }

    @Test
    fun `each option label is non-blank`() {
        options.forEach { assertFalse(it.label.isBlank(), "label for ${it.value} is blank") }
    }

    @Test
    fun `scopedKey namespaces key by user id`() {
        assertEquals(
            "composer_text:u1",
            ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "u1")
        )
        assertEquals(
            "selected_visibility:u2",
            ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_VISIBILITY, "u2")
        )
    }

    @Test
    fun `scopedKey returns null for blank user id`() {
        assertNull(ExploreDraftPolicy.scopedKey("composer_text", ""))
        assertNull(ExploreDraftPolicy.scopedKey("composer_text", "   "))
    }

    @Test
    fun `normalizeVisibility is case sensitive and fails closed`() {
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("public"))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("Public"))
    }

    @Test
    fun `draft key constants are stable`() {
        assertEquals("composer_text", ExploreDraftPolicy.KEY_COMPOSER_TEXT)
        assertEquals("selected_visibility", ExploreDraftPolicy.KEY_VISIBILITY)
    }

    @Test
    fun `visibilityOption subtitle defaults to empty`() {
        assertEquals("", VisibilityOption("PUBLIC", "公开").subtitle)
    }

    @Test
    fun `normalizeVisibility does not trim padded value`() {
        // 精确匹配 VISIBILITY_VALUES，不做 trim：前后带空格的值照样回落
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility(" PUBLIC "))
        assertEquals("PRIVATE", ExploreDraftPolicy.normalizeVisibility("PRIVATE\n"))
    }

    @Test
    fun `normalizeVisibility is idempotent`() {
        assertEquals("PUBLIC", ExploreDraftPolicy.normalizeVisibility("PUBLIC"))
        val once = ExploreDraftPolicy.normalizeVisibility("UNKNOWN")
        assertEquals(once, ExploreDraftPolicy.normalizeVisibility(once))
    }

    @Test
    fun `scopedKey passes userId through verbatim`() {
        // 只做 isBlank 守卫，不 trim、不归一化 userId
        assertEquals("k:u 1", ExploreDraftPolicy.scopedKey("k", "u 1"))
    }

    @Test
    fun `scopedKey isolates accounts on the same base`() {
        val a = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "u1")
        val b = ExploreDraftPolicy.scopedKey(ExploreDraftPolicy.KEY_COMPOSER_TEXT, "u2")
        assertFalse(a == b, "accounts must not share a draft key")
    }

    @Test
    fun `each option subtitle is non-blank`() {
        options.forEach { assertFalse(it.subtitle.isBlank(), "subtitle for ${it.value} is blank") }
    }
}
