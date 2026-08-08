package com.maodouchat.explore

import com.maodouchat.ui.screen.explore.VisibilityOption
import com.maodouchat.ui.screen.explore.ExploreDraftPolicy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
