package com.maodouchat.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Admin E2E selectors (11 tabs, no `rules`) must stay aligned with the HTML
 * shipped on main. This does not boot Ktor/Playwright, so it stays stable
 * against parallel visual work (XAL-13 / XAL-14).
 */
class AdminNavTabsRegressionTest {

    @Test
    fun `admin html ships the current nav tabs and no rules tab`() {
        val html = requireNotNull(javaClass.classLoader.getResource("admin/admin.html")) {
            "admin/admin.html missing from classpath"
        }.readText()

        val tabs = TAB_ATTR.findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(EXPECTED_TABS, tabs)
        assertFalse("rules" in tabs)
        assertFalse(html.contains("""data-tab="rules""""))
    }

    @Test
    fun `admin js still exposes the e2e selector ids used by each remaining tab`() {
        val js = requireNotNull(javaClass.classLoader.getResource("admin/admin.js")) {
            "admin/admin.js missing from classpath"
        }.readText()
        for (needle in E2E_SELECTOR_NEEDLES) {
            assertTrue(js.contains(needle), "admin.js must still emit $needle")
        }
    }

    private companion object {
        val TAB_ATTR = Regex("""data-tab="([^"]+)"""")
        val EXPECTED_TABS = listOf(
            "dashboard",
            "users",
            "online",
            "ranking",
            "content",
            "moderation",
            "risk",
            "announcements",
            "system",
            "diagnostics",
            "audit",
        )
        val E2E_SELECTOR_NEEDLES = listOf(
            "id=\"search-btn-' + kind + '\"",
            "id=\"filter-reports\"",
            "id=\"b6-ann-create\"",
            "id=\"ops-copy-save\"",
            "id=\"settings-save\"",
            "id=\"filter-risk-events\"",
            "id=\"ai-usage-search\"",
            "id=\"audit-export\"",
            "/api/admin/chats?groupOnly=true",
            "requiresTotp",
            "settings-advanced",
        )
    }
}
