package com.maodouchat.chatdetail

import com.maodouchat.data.model.User
import com.maodouchat.ui.screen.chatdetail.InsertResult
import com.maodouchat.ui.screen.chatdetail.MentionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionPolicyTest {

    private val alice = User(id = "u1", name = "Alice")
    private val bob = User(id = "u2", name = "Bob Chen")
    private val me = User(id = "me", name = "Me")
    private val members = listOf(me, alice, bob)

    @Test
    fun activeQuery_detectsAtFilter() {
        val q = MentionPolicy.activeQuery("hi @Al", cursor = 6)
        assertEquals(3, q!!.atIndex)
        assertEquals("Al", q.filter)
        assertNull(MentionPolicy.activeQuery("hi @Al ", cursor = 7))
        assertNull(MentionPolicy.activeQuery("a@b", cursor = 3))
    }

    @Test
    fun shouldShowPicker_groupOnly_notForAiCommand() {
        assertFalse(MentionPolicy.shouldShowPicker("hello", isGroupChat = true))
        assertTrue(MentionPolicy.shouldShowPicker("hi @", isGroupChat = true))
        assertFalse(MentionPolicy.shouldShowPicker("hi @", isGroupChat = false))
        assertFalse(MentionPolicy.shouldShowPicker("@AI summarize", isGroupChat = true))
        assertTrue(MentionPolicy.shouldShowPicker("note @A", isGroupChat = true))
    }

    @Test
    fun filterCandidates_includesEveryoneAndExcludesSelf() {
        val all = MentionPolicy.filterCandidates(members, currentUserId = "me", filter = "")
        assertTrue(all.any { it.isEveryone })
        assertFalse(all.any { it.userId == "me" })
        assertTrue(all.any { it.userId == "u1" })

        val al = MentionPolicy.filterCandidates(members, "me", "al")
        assertTrue(al.any { it.userId == "u1" })
        assertFalse(al.any { it.userId == "u2" })
    }

    @Test
    fun insertMention_replacesQuery() {
        val text = "hi @Al"
        val q = MentionPolicy.activeQuery(text)!!
        val r = MentionPolicy.insertMention(text, text.length, "Alice", q)
        assertEquals("hi @Alice ", r.text)
        assertEquals(r.text.length, r.cursor)
    }

    @Test
    fun extractMentionIds_namesAndEveryone() {
        val ids = MentionPolicy.extractMentionIds(
            "ping @Alice and @everyone please",
            members,
            currentUserId = "me",
        )
        assertTrue(ids.contains("u1"))
        assertTrue(ids.contains(MentionPolicy.EVERYONE_ID))

        val bob = MentionPolicy.extractMentionIds("@Bob Chen ok", members, "me")
        assertEquals(listOf("u2"), bob)

        assertTrue(MentionPolicy.extractMentionIds("@AI what", members, "me").isEmpty())
    }

    @Test
    fun shouldHighlightMention_selfOrEveryone() {
        assertTrue(
            MentionPolicy.shouldHighlightMention(
                listOf("me"),
                currentUserId = "me",
                notificationsMuted = false,
            )
        )
        assertTrue(
            MentionPolicy.shouldHighlightMention(
                listOf(MentionPolicy.EVERYONE_ID),
                "me",
                false,
            )
        )
        assertFalse(
            MentionPolicy.shouldHighlightMention(listOf("u1"), "me", false)
        )
        assertFalse(
            MentionPolicy.shouldHighlightMention(listOf("me"), "me", notificationsMuted = true)
        )
    }

    @Test
    fun activeQuery_resolvesMidCursor_and_atSignAtStart() {
        val mid = MentionPolicy.activeQuery("hi @Alice", cursor = 6)!!
        assertEquals(3, mid.atIndex)
        assertEquals("Al", mid.filter)

        val atStart = MentionPolicy.activeQuery("@Al", cursor = 3)!!
        assertEquals(0, atStart.atIndex)
        assertEquals("Al", atStart.filter)
    }

    @Test
    fun activeQuery_aiPrefix_suppressed_caseInsensitive() {
        assertNull(MentionPolicy.activeQuery("@AI", cursor = 3))
        assertNull(MentionPolicy.activeQuery("hi @ai", cursor = 6))
        assertNull(MentionPolicy.activeQuery("hi @Al\nx", cursor = 7))
    }

    @Test
    fun insertMention_blankName_keepsText_and_implicitQuery() {
        assertEquals(
            InsertResult("hi @Al", 6),
            MentionPolicy.insertMention("hi @Al", 6, "   "),
        )
        val r = MentionPolicy.insertMention("hi @Al", 6, "Alice")
        assertEquals("hi @Alice ", r.text)
        assertEquals(r.text.length, r.cursor)
    }

    @Test
    fun extractMentionIds_longestNameWins_and_boundaryStrict() {
        val carol = User(id = "u3", name = "Al")
        val ids = MentionPolicy.extractMentionIds("@Alice hi", members + carol, "me")
        assertEquals(listOf("u1"), ids)

        assertTrue(MentionPolicy.extractMentionIds("hi @AliceX ok", members, "me").isEmpty())
        val punct = MentionPolicy.extractMentionIds("hi @Alice, ok", members, "me")
        assertEquals(listOf("u1"), punct)
    }

    @Test
    fun filterCandidates_limit_includeEveryoneFalse_blankNameFallsBackToId() {
        val limited = MentionPolicy.filterCandidates(members, "me", "", limit = 1)
        assertEquals(1, limited.size)

        val noEveryone = MentionPolicy.filterCandidates(members, "me", "", includeEveryone = false)
        assertFalse(noEveryone.any { it.isEveryone })

        val nameless = MentionPolicy.filterCandidates(
            listOf(me, User(id = "u9", name = "")),
            "me",
            "",
            includeEveryone = false,
        )
        assertEquals("u9", nameless.single().displayName)
    }

    @Test
    fun displayTokenForUser_usesName_or_fallsBackToId() {
        assertEquals("@Alice", MentionPolicy.displayTokenForUser(alice))
        assertEquals("@u9", MentionPolicy.displayTokenForUser(User(id = "u9", name = "")))
    }

    @Test
    fun shouldShowPicker_lowercaseAiCommandSuppressed_laterMentionStillShows() {
        assertFalse(MentionPolicy.shouldShowPicker("@ai summarize", isGroupChat = true))
        assertTrue(MentionPolicy.shouldShowPicker("@AI summarize @Al", isGroupChat = true))
    }

    @Test
    fun filterCandidates_everyoneAliases_match_and_resultsSorted() {
        val people = listOf(me, User(id = "u5", name = "Zoe"), User(id = "u6", name = "amy"))

        assertTrue(MentionPolicy.filterCandidates(people, "me", "eve").any { it.isEveryone })
        assertTrue(MentionPolicy.filterCandidates(people, "me", "所有人").any { it.isEveryone })
        assertTrue(MentionPolicy.filterCandidates(people, "me", "all").any { it.isEveryone })
        assertFalse(MentionPolicy.filterCandidates(people, "me", "xyz").any { it.isEveryone })

        val all = MentionPolicy.filterCandidates(people, "me", "", includeEveryone = false)
        assertEquals(listOf("amy", "Zoe"), all.map { it.displayName })
    }

    @Test
    fun extractMentionIds_legacyUserId_and_chineseEveryone() {
        assertEquals(listOf("u2"), MentionPolicy.extractMentionIds("@u2 ok", members, "me"))
        assertEquals(
            listOf(MentionPolicy.EVERYONE_ID),
            MentionPolicy.extractMentionIds("hi @所有人", members, "me"),
        )
        assertEquals(listOf("u1"), MentionPolicy.extractMentionIds("@AI what @Alice", members, "me"))
    }

    @Test
    fun activeQuery_emptyText_zeroCursor_and_cursorOnWhitespace_returnNull() {
        assertNull(MentionPolicy.activeQuery(""))
        assertNull(MentionPolicy.activeQuery("hi", cursor = 0))
        assertNull(MentionPolicy.activeQuery("hi @Al x", cursor = 6))
    }

    @Test
    fun extractMentionIds_allAlias_mapsToEveryone_and_repeatsDeduped() {
        assertEquals(
            listOf(MentionPolicy.EVERYONE_ID),
            MentionPolicy.extractMentionIds("hi @all", members, "me"),
        )
        assertEquals(
            listOf("u1"),
            MentionPolicy.extractMentionIds("@Alice @Alice ok", members, "me"),
        )
    }

    @Test
    fun filterCandidates_trimsFilter_and_matchesByUserId() {
        val byId = MentionPolicy.filterCandidates(members, "me", " u2 ")
        assertTrue(byId.any { it.userId == "u2" })
        assertFalse(byId.any { it.userId == "u1" })
        assertFalse(byId.any { it.isEveryone })
        val both = MentionPolicy.filterCandidates(members, "me", "U")
        assertEquals(listOf("u1", "u2"), both.map { it.userId })
    }

    @Test
    fun extractMentionIds_skipsEmailLikeTokens() {
        assertTrue(MentionPolicy.extractMentionIds("mail to a@b ok", members, "me").isEmpty())
    }

    @Test
    fun insertMention_preservesTextAfterCursor_and_noQueryKeepsText() {
        val r = MentionPolicy.insertMention("hi @Al rest", 6, "Alice")
        assertEquals("hi @Alice  rest", r.text)
        assertEquals(10, r.cursor)
        assertEquals(
            InsertResult("hello", 5),
            MentionPolicy.insertMention("hello", 5, "Alice"),
        )
    }

    @Test
    fun shouldHighlightMention_blankCurrentUser_and_emptyMentions_neverHighlight() {
        assertFalse(MentionPolicy.shouldHighlightMention(listOf("me"), "", false))
        assertFalse(MentionPolicy.shouldHighlightMention(emptyList(), "me", false))
        assertFalse(
            MentionPolicy.shouldHighlightMention(
                listOf(MentionPolicy.EVERYONE_ID),
                "me",
                notificationsMuted = true,
            )
        )
    }
}
