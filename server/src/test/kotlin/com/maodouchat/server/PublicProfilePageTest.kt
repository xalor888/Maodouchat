package com.maodouchat.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XAL-42：公开分享页模板与样式走 classpath，不依赖 Ktor/H2。
 */
class PublicProfilePageTest {

    @Test
    fun `profile html is a static card template with tokens dark mode and no dialogs`() {
        val html = requireNotNull(javaClass.classLoader.getResource("public/profile.html")) {
            "public/profile.html missing from classpath"
        }.readText()

        for (token in REQUIRED_TOKENS) {
            assertTrue(html.contains("{{$token}}"), "profile.html must include {{$token}}")
        }
        assertTrue(html.contains("""rel="stylesheet" href="/assets/profile.css""""), html)
        assertTrue(html.contains("Content-Security-Policy"), html)
        assertTrue(html.contains("style-src 'self'"), html)
        assertFalse(html.contains("unsafe-inline"), html)
        assertTrue(html.contains("og:title"), html)
        assertTrue(html.contains("twitter:card"), html)
        assertTrue(html.contains("{{DEEP_LINK}}"), html)
        assertTrue(html.contains("{{INTENT_LINK}}"), html)
        assertFalse(html.contains("alert("), html)
        assertFalse(html.contains("confirm("), html)
        assertFalse(html.contains("prompt("), html)
        assertFalse(html.contains("<script"), html)
        assertTrue(html.contains("{{BODY_CLASS}}"), html)
        assertTrue(html.contains("card-profile"), html)
        assertTrue(html.contains("card-error"), html)
        assertTrue(html.contains("card-empty"), html)
        assertTrue(html.contains("不会显示在线状态"), html)
    }

    @Test
    fun `profile css ships liquid-glass card and prefers-color-scheme dark`() {
        val css = requireNotNull(javaClass.classLoader.getResource("public/assets/profile.css")) {
            "public/assets/profile.css missing from classpath"
        }.readText()
        assertTrue(css.contains("prefers-color-scheme: dark"), css)
        assertTrue(css.contains("backdrop-filter"), css)
        assertTrue(css.contains(".card"), css)
        assertTrue(css.contains(".state-profile"), css)
        assertTrue(css.contains(".state-error"), css)
        assertTrue(css.contains(".no-avatar"), css)
        assertFalse(css.contains("alert("), css)
    }

    private companion object {
        val REQUIRED_TOKENS = listOf(
            "TITLE",
            "DESCRIPTION",
            "CANONICAL",
            "OG_URL",
            "OG_IMAGE_TAGS",
            "TWITTER_CARD",
            "TWITTER_IMAGE_TAG",
            "BODY_CLASS",
            "BASE_HREF",
            "INITIAL",
            "AVATAR_IMG",
            "NAME",
            "USERNAME",
            "STATUS",
            "DEEP_LINK",
            "INTENT_LINK",
            "ERROR_TITLE",
            "ERROR_DESC",
            "YEAR",
        )
    }
}
