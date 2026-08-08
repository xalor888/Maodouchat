package com.maodouchat.chatdetail

import com.maodouchat.data.model.User
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
}
