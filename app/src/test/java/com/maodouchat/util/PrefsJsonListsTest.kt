package com.maodouchat.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrefsJsonListsTest {

    @Test
    fun roundTrip() {
        val items = listOf("a", "b-c_1", "中文", "x".repeat(200))
        assertEquals(items, PrefsJsonLists.decode(PrefsJsonLists.encode(items)))
    }

    @Test
    fun emptyRoundTrip() {
        assertEquals("[]", PrefsJsonLists.encode(emptyList()))
        assertTrue(PrefsJsonLists.decode("[]").isEmpty())
    }

    @Test
    fun decodeTrimsAndDropsBlanks() {
        assertEquals(
            listOf("a", "b"),
            PrefsJsonLists.decode("""["  a  ", "", "   ", "b"]"""),
        )
    }

    @Test
    fun corruptInputDecodesToEmpty() {
        assertTrue(PrefsJsonLists.decode("").isEmpty())
        assertTrue(PrefsJsonLists.decode("not-json{{{").isEmpty())
        assertTrue(PrefsJsonLists.decode("{\"k\":1}").isEmpty())
        assertTrue(PrefsJsonLists.decode("null").isEmpty())
    }
}
