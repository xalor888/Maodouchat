package com.maodouchat.domain.conversation

import com.maodouchat.domain.conversation.ExploreRelativeTimeBucket.Days
import com.maodouchat.domain.conversation.ExploreRelativeTimeBucket.Hours
import com.maodouchat.domain.conversation.ExploreRelativeTimeBucket.JustNow
import com.maodouchat.domain.conversation.ExploreRelativeTimeBucket.Minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreRelativeTimeTest {

    @Test
    fun `just now covers zero and just under a minute`() {
        assertEquals(JustNow, relativeTimeBucket(0))
        assertEquals(JustNow, relativeTimeBucket(59_999))
    }

    @Test
    fun `future timestamps fall back to just now`() {
        assertEquals(JustNow, relativeTimeBucket(-1))
        assertEquals(JustNow, relativeTimeBucket(-86_400_000))
    }

    @Test
    fun `minutes bucket starts at sixty seconds`() {
        assertEquals(Minutes(1), relativeTimeBucket(60_000))
        assertEquals(Minutes(59), relativeTimeBucket(3_599_999))
    }

    @Test
    fun `hours bucket starts at one hour`() {
        assertEquals(Hours(1), relativeTimeBucket(3_600_000))
        assertEquals(Hours(23), relativeTimeBucket(86_399_999))
    }

    @Test
    fun `days bucket starts at one day`() {
        assertEquals(Days(1), relativeTimeBucket(86_400_000))
        assertEquals(Days(3), relativeTimeBucket(3L * 86_400_000))
    }

    @Test
    fun `the four buckets are distinct kinds`() {
        val kinds = listOf(
            relativeTimeBucket(0)::class,
            relativeTimeBucket(60_000)::class,
            relativeTimeBucket(3_600_000)::class,
            relativeTimeBucket(86_400_000)::class,
        )
        assertEquals(4, kinds.toSet().size)
    }

    @Test
    fun `contacts maps to contacts`() {
        assertEquals(ExploreVisibilityOption.Contacts, exploreVisibilityOption("CONTACTS"))
    }

    @Test
    fun `private maps to private`() {
        assertEquals(ExploreVisibilityOption.Private, exploreVisibilityOption("PRIVATE"))
    }

    @Test
    fun `public and unknown values fall back to public`() {
        assertEquals(ExploreVisibilityOption.Public, exploreVisibilityOption("PUBLIC"))
        assertEquals(ExploreVisibilityOption.Public, exploreVisibilityOption("FRIENDS"))
        assertEquals(ExploreVisibilityOption.Public, exploreVisibilityOption(""))
    }

    @Test
    fun `visibility matching is case sensitive`() {
        assertEquals(ExploreVisibilityOption.Public, exploreVisibilityOption("contacts"))
        assertEquals(ExploreVisibilityOption.Public, exploreVisibilityOption("private"))
    }
}
