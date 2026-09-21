package com.maodouchat.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * G179：`normalizeVisibility` 的测试（G179 刚从两处同构实现收敛而来）。
 *
 * 重点是把**两个方向相反的回落**都钉住：调用方显式传 PUBLIC 就回落 PUBLIC，
 * 显式传 PRIVATE 就回落 PRIVATE。将来若要统一，这两条会先红。
 */
class SettingsVisibilityPolicyTest {

    @Test
    fun `the three known values pass through unchanged`() {
        listOf("PUBLIC", "CONTACTS", "PRIVATE").forEach { v ->
            assertEquals(v, normalizeVisibility(v, "PUBLIC"))
            assertEquals(v, normalizeVisibility(v, "PRIVATE"))
        }
    }

    @Test
    fun `unknown values fall back to the caller supplied default`() {
        // 设置页：回落公开
        assertEquals("PUBLIC", normalizeVisibility("SOMETHING_NEW", "PUBLIC"))
        // 发布器：回落私密
        assertEquals("PRIVATE", normalizeVisibility("SOMETHING_NEW", "PRIVATE"))
    }

    @Test
    fun `blank values also fall back`() {
        assertEquals("PUBLIC", normalizeVisibility("", "PUBLIC"))
        assertEquals("PRIVATE", normalizeVisibility("", "PRIVATE"))
        assertEquals("PUBLIC", normalizeVisibility("   ", "PUBLIC"))
    }

    @Test
    fun `matching is case sensitive`() {
        // 全小写/混合大小写都不算认识的值——图里的键必须与协议完全一致
        assertEquals("PUBLIC", normalizeVisibility("public", "PUBLIC"))
        assertEquals("PUBLIC", normalizeVisibility("Public", "PUBLIC"))
        assertEquals("PUBLIC", normalizeVisibility("CONTACTS ", "PUBLIC"))
    }

    @Test
    fun `the two fallback directions are genuinely different`() {
        // 把同一输入喂给两个方向，结果必须不同——否则这处分歧就消失了
        assertEquals(
            false,
            normalizeVisibility("weird", "PUBLIC") == normalizeVisibility("weird", "PRIVATE"),
        )
    }

    @Test
    fun `the recognised set is exactly the three protocol values`() {
        assertEquals(setOf("PUBLIC", "CONTACTS", "PRIVATE"), VISIBILITY_VALUES)
    }
}
