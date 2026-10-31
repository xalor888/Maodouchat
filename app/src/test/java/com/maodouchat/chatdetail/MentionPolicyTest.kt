package com.maodouchat.chatdetail

import com.maodouchat.data.model.User
import com.maodouchat.ui.screen.chatdetail.ActiveQuery
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
        // 光标落在空白上不算进行中 query（见同文件 activeQuery 空白断言），
        // 故用光标落在 token 内的情形验证光标后文本保留：cursor=6 时 filter 为 "Al"，
        // 插入后光标后 "x" 原样保留。
        val r = MentionPolicy.insertMention("hi @Alx", 6, "Alice")
        assertEquals("hi @Alice x", r.text)
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

    @Test
    fun activeQuery_cursorCoercion_loneAtSign_and_negativeCursor() {
        val coerced = MentionPolicy.activeQuery("hi @Al", cursor = 99)!!
        assertEquals(3, coerced.atIndex)
        assertEquals("Al", coerced.filter)

        val lone = MentionPolicy.activeQuery("@", cursor = 1)!!
        assertEquals(0, lone.atIndex)
        assertEquals("", lone.filter)

        assertNull(MentionPolicy.activeQuery("hi @Al", cursor = -5))
    }

    @Test
    fun activeQuery_doubleAtSign_and_nonBoundaryAtSign_rejected() {
        assertNull(MentionPolicy.activeQuery("@@Al", cursor = 4))
        assertNull(MentionPolicy.activeQuery("x@Al", cursor = 4))
        assertNull(MentionPolicy.activeQuery("hi@Al", cursor = 5))
    }

    @Test
    fun shouldShowPicker_midTextBareAiSuppressed_midMentionShows() {
        assertFalse(MentionPolicy.shouldShowPicker("hi @AI", isGroupChat = true))
        assertFalse(MentionPolicy.shouldShowPicker("hi @AI x", isGroupChat = true))
        assertTrue(MentionPolicy.shouldShowPicker("hi @Al", isGroupChat = true))
    }

    @Test
    fun filterCandidates_excludesBlankIdAndEveryoneIdParticipants() {
        val people = listOf(
            me,
            User(id = "", name = "Ghost"),
            User(id = MentionPolicy.EVERYONE_ID, name = "Fake"),
            alice,
        )
        val result = MentionPolicy.filterCandidates(people, "me", "")
        assertTrue(result.none { it.userId.isBlank() })
        assertEquals(1, result.count { it.isEveryone })
        assertTrue(result.any { it.userId == "u1" })
    }

    @Test
    fun extractMentionIds_punctuationBoundaries_caseSensitive_selfExcluded() {
        assertEquals(
            listOf("u1", "u2"),
            MentionPolicy.extractMentionIds("@Alice! @Bob Chen? @Alice。", members, "me"),
        )
        assertTrue(MentionPolicy.extractMentionIds("@alice hi", members, "me").isEmpty())
        assertEquals(
            listOf(MentionPolicy.EVERYONE_ID),
            MentionPolicy.extractMentionIds("@everyone @me", members, "me"),
        )
    }

    @Test
    fun activeQuery_midWordCursor_returns_partial_filter() {
        val partial = MentionPolicy.activeQuery("hi @Alice", cursor = 5)!!
        assertEquals(3, partial.atIndex)
        assertEquals("A", partial.filter)
        val bare = MentionPolicy.activeQuery("hi @Alice", cursor = 4)!!
        assertEquals(3, bare.atIndex)
        assertEquals("", bare.filter)
    }

    @Test
    fun activeQuery_ai_trailing_space_and_cursor_on_at_sign_return_null() {
        assertNull(MentionPolicy.activeQuery("@AI ", cursor = 4))
        assertNull(MentionPolicy.activeQuery("hi @Al", cursor = 3))
    }

    @Test
    fun insertMention_explicitQuery_overrides_detection() {
        val r = MentionPolicy.insertMention("hi @Al", 6, "Bob Chen", ActiveQuery(3, "Al"))
        assertEquals("hi @Bob Chen ", r.text)
        assertEquals(13, r.cursor)
        assertEquals(
            InsertResult("hi @Al", 6),
            MentionPolicy.insertMention("hi @Al", 6, "  ", ActiveQuery(3, "Al")),
        )
    }

    @Test
    fun extractMentionIds_appearance_order_kept() {
        assertEquals(
            listOf("u2", "u1"),
            MentionPolicy.extractMentionIds("@Bob Chen @Alice", members, "me"),
        )
        assertEquals(
            listOf(MentionPolicy.EVERYONE_ID, "u1"),
            MentionPolicy.extractMentionIds("@everyone @Alice", members, "me"),
        )
    }

    @Test
    fun filterCandidates_limit_keeps_everyone_first() {
        val one = MentionPolicy.filterCandidates(members, "me", "", limit = 1)
        assertEquals(1, one.size)
        assertTrue(one.single().isEveryone)
        val two = MentionPolicy.filterCandidates(members, "me", "", limit = 2)
        assertEquals(listOf(MentionPolicy.EVERYONE_ID, "u1"), two.map { it.userId })
    }

    @Test
    fun activeQuery_aiLookalikeName_isNotAssistantCommand() {
        val aix = MentionPolicy.activeQuery("hi @AIX", cursor = 8)!!
        assertEquals(3, aix.atIndex)
        assertEquals("AIX", aix.filter)
        assertNull(MentionPolicy.activeQuery("hi @AI x", cursor = 8))
        assertNull(MentionPolicy.activeQuery("hi @ai x", cursor = 8))
    }

    @Test
    fun filterCandidates_limitZero_stillYieldsEveryone_and_uppercaseAliasMatches() {
        val zero = MentionPolicy.filterCandidates(members, "me", "", limit = 0)
        assertEquals(1, zero.size)
        assertTrue(zero.single().isEveryone)
        assertTrue(
            MentionPolicy.filterCandidates(members, "me", "", includeEveryone = false, limit = 0).isEmpty()
        )
        val alias = MentionPolicy.filterCandidates(members, "me", "EVERY")
        assertEquals(1, alias.size)
        assertTrue(alias.single().isEveryone)
    }

    @Test
    fun extractMentionIds_adjacentMentions_yieldNothing_and_duplicateNameFirstWins() {
        assertTrue(MentionPolicy.extractMentionIds("@Alice@Bob Chen", members, "me").isEmpty())
        val dup = MentionPolicy.extractMentionIds(
            "@Alice",
            members + User(id = "u3", name = "Alice"),
            "me",
        )
        assertEquals(listOf("u1"), dup)
    }

    @Test
    fun insertMention_cursorCoerced_toTextBounds() {
        val pastEnd = MentionPolicy.insertMention("hi @Al", 99, "Alice")
        assertEquals("hi @Alice ", pastEnd.text)
        assertEquals(10, pastEnd.cursor)
        assertEquals(
            InsertResult("hi @Al", -5),
            MentionPolicy.insertMention("hi @Al", -5, "Alice"),
        )
    }

    @Test
    fun highlight_everyoneWithBlankCurrentUser_neverHighlights_and_displayTokenKeepsSpaces() {
        assertFalse(
            MentionPolicy.shouldHighlightMention(
                listOf(MentionPolicy.EVERYONE_ID),
                "",
                false,
            )
        )
        assertEquals("@Bob Chen", MentionPolicy.displayTokenForUser(bob))
    }

    @Test
    fun activeQuery_newlineAndTabBeforeAt_countAsBoundary() {
        val nl = MentionPolicy.activeQuery("hi\n@Al", cursor = 6)!!
        assertEquals(3, nl.atIndex)
        assertEquals("Al", nl.filter)
        val tab = MentionPolicy.activeQuery("hi\t@Al", cursor = 6)!!
        assertEquals(3, tab.atIndex)
        assertEquals("Al", tab.filter)
    }

    @Test
    fun shouldShowPicker_bareAiCommand_suppressed() {
        assertFalse(MentionPolicy.shouldShowPicker("@AI", isGroupChat = true))
        assertFalse(MentionPolicy.shouldShowPicker("@ai", isGroupChat = true))
    }

    @Test
    fun insertMention_displayNameInternalSpacesKept_outerTrimmed() {
        val r = MentionPolicy.insertMention("hi @Al", 6, "  Mary  Jane ")
        assertEquals("hi @Mary  Jane ", r.text)
        assertEquals(15, r.cursor)
    }

    @Test
    fun extractMentionIds_mentionAtEndOfText_matches() {
        assertEquals(listOf("u1"), MentionPolicy.extractMentionIds("@Alice", members, "me"))
        assertEquals(listOf("u2"), MentionPolicy.extractMentionIds("@Bob Chen", members, "me"))
        assertEquals(listOf("u1"), MentionPolicy.extractMentionIds("hi @Alice", members, "me"))
    }

    @Test
    fun shouldHighlightMention_mutedSuppressesEveryoneAndSelf() {
        assertFalse(
            MentionPolicy.shouldHighlightMention(
                listOf(MentionPolicy.EVERYONE_ID),
                "me",
                true,
            )
        )
        assertFalse(MentionPolicy.shouldHighlightMention(listOf("me"), "me", true))
        assertTrue(MentionPolicy.shouldHighlightMention(listOf("me"), "me", false))
    }

    @Test
    fun filterCandidates_everyoneAlias_partialChineseMatches() {
        val onlyEveryone = MentionPolicy.filterCandidates(members, "me", "\u4eba")
        assertEquals(1, onlyEveryone.size)
        assertTrue(onlyEveryone.single().isEveryone)
        val prefix = MentionPolicy.filterCandidates(members, "me", "\u6240\u6709")
        assertEquals(1, prefix.size)
        assertTrue(prefix.single().isEveryone)
        assertTrue(MentionPolicy.filterCandidates(members, "me", "very").isEmpty())
    }

    @Test
    fun extractMentionIds_everyoneCaseInsensitiveAndBoundaryStrict() {
        assertEquals(listOf(MentionPolicy.EVERYONE_ID), MentionPolicy.extractMentionIds("@EVERYONE", members, "me"))
        assertEquals(listOf(MentionPolicy.EVERYONE_ID), MentionPolicy.extractMentionIds("@Everyone!", members, "me"))
        assertEquals(listOf(MentionPolicy.EVERYONE_ID), MentionPolicy.extractMentionIds("@ALL", members, "me"))
        assertTrue(MentionPolicy.extractMentionIds("@everyoneX", members, "me").isEmpty())
        assertTrue(MentionPolicy.extractMentionIds("@allx", members, "me").isEmpty())
    }

    @Test
    fun insertMention_explicitQuery_usesCursorForTrailingText() {
        // 显式 query 只定替换起点，query 后的原文从 cursor 处保留，不是 query 末尾。
        val mid = MentionPolicy.insertMention("hi @Alice", 5, "Bob", ActiveQuery(3, "Al"))
        assertEquals("hi @Bob lice", mid.text)
        assertEquals(8, mid.cursor)
        val atStart = MentionPolicy.insertMention("hi @Alice", 3, "Bob", ActiveQuery(3, "Al"))
        assertEquals("hi @Bob @Alice", atStart.text)
        assertEquals(8, atStart.cursor)
    }

    @Test
    fun shouldShowPicker_leadingSpacesBeforeAiCommandStillSuppressed() {
        assertFalse(MentionPolicy.shouldShowPicker("  @AI summarize", isGroupChat = true))
        assertTrue(MentionPolicy.shouldShowPicker("  @AI summarize @Al", isGroupChat = true))
    }

    @Test
    fun extractMentionIds_colonAndSemicolonBoundaries() {
        assertEquals(
            listOf("u1", "u2"),
            MentionPolicy.extractMentionIds("@Alice: @Bob Chen;", members, "me"),
        )
        assertEquals(
            listOf("u1", "u2"),
            MentionPolicy.extractMentionIds("@Alice. @Bob Chen\uff0c", members, "me"),
        )
    }

    @Test
    fun activeQuery_lastMentionWins_whenTwoQueriesInText() {
        val q = MentionPolicy.activeQuery("hi @Al @Bo", cursor = 9)!!
        assertEquals(7, q.atIndex)
        assertEquals("B", q.filter)
    }

    @Test
    fun extractMentionIds_userEveryoneUser_dedupesKeepingOrder() {
        assertEquals(
            listOf("u1", MentionPolicy.EVERYONE_ID),
            MentionPolicy.extractMentionIds("@Alice @everyone @Alice", members, "me"),
        )
    }

    @Test
    fun shouldHighlightMention_mixedList_highlightsOnlyWithSelfOrEveryone() {
        assertTrue(MentionPolicy.shouldHighlightMention(listOf("u1", "me"), "me", false))
        assertTrue(MentionPolicy.shouldHighlightMention(listOf("u1", MentionPolicy.EVERYONE_ID), "me", false))
        assertFalse(MentionPolicy.shouldHighlightMention(listOf("u1", "u2"), "me", false))
    }

    @Test
    fun insertMention_keepsTextAfterCursor_afterInsertion() {
        val r = MentionPolicy.insertMention("hi @Alx there", 6, "Alice")
        assertEquals("hi @Alice x there", r.text)
        assertEquals(10, r.cursor)
    }

    @Test
    fun shouldShowPicker_cursorOnWhitespace_suppresses_cursorInsideToken_shows() {
        assertFalse(MentionPolicy.shouldShowPicker("hi @Al x", isGroupChat = true, cursor = 6))
        assertTrue(MentionPolicy.shouldShowPicker("hi @Al x", isGroupChat = true, cursor = 5))
    }

}
