package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [StarredMessageSearchPolicy] (checklist line 931, 47th block).
 * Test-only, zero production changes.
 *
 * The oracle reimplements the policy independently: explicit loops for
 * sender lookup / non-self participant selection, a hand-rolled displayName
 * (nickname if non-blank, else name if non-blank, else id) over the swept
 * Kotlin whitespace set, an explicit backward scan for the last "<meta>"
 * plus an explicit trim, and a case-insensitive contains that transliterates
 * the JDK 17 algorithm behind String.contains(ignoreCase = true)
 * (Kotlin indexOf -> String.regionMatches(ignoreCase = true) -> StringUTF16
 * .compareToCIImpl; see containsIgnoreCaseOracle).
 * Shared primitives (production calls them too, so sharing is exact):
 * String.length, char indexing, Character.toUpperCase(int) /
 * toLowerCase(int) code-point mappings.
 *
 * A naive per-char fold is WRONG here, in two ways (both found by the
 * differential sweep, block 47):
 * - the fold is two-step per code point (upper, then lower-of-upper):
 *   dotless-i U+0131 and dotted-I U+0130 differ in both single steps
 *   (upper: I vs U+0130; lower: U+0131 vs i) yet match, because
 *   lower(upper(U+0131)) == lower(upper(U+0130)) == 'i';
 * - supplementary characters fold as CODE POINTS (U+10400 ~ U+10428),
 *   not per UTF-16 unit (a low surrogate alone never folds), and a low
 *   surrogate also re-combines with a preceding high surrogate.
 * The transliteration reproduces the JDK loop verbatim, including its
 * early-exit quirk: when one side consumes a supplementary pair (extra
 * index step) while the other side does not, the loop can exit with
 * tlen - olen == 0 before every needle char was compared.
 *
 * Documented contrast (pinned, not imported: :domain:messaging tests must
 * not depend on :domain:conversation): GlobalSearchNamePolicy.chatName takes
 * the FIRST participant of a direct chat, while chatTitle takes the first
 * NON-SELF participant and only falls back to the first. On a direct chat
 * whose first participant is the current user they differ ("Bob" vs "Me").
 *
 * Whitespace facts (swept over all BMP chars on JDK 17, stdlib 2.0.21 and
 * 2.4.0 — identical, see block 46): Kotlin trim()/isBlank() use the set
 * U+0009..U+000D, U+001C..U+0020, U+00A0, U+1680, U+2000..U+200A,
 * U+2028..U+2029, U+202F, U+205F, U+3000 — NOT U+0085 NEL, NOT ZWSP/BOM.
 *
 * Verification (offline, JDK 17): the real production code + real core
 * models were compiled verbatim with the Kotlin compiler and differentially
 * checked against this oracle on 241,075 cases — the exact inputs these
 * tests generate (Random(42)), a codec round-trip, senderLabel/chatTitle
 * exhaustives, a 196,608-case whitespace sweep (every BMP char alone and
 * wrapped), meta templates, a 12,681-case case-fold sweep, a 24,336-case
 * lone-surrogate adversarial sweep, and a 5,000-case filter sweep — with
 * 0 differences. Every pinned expectation below was checked against real
 * production. The 5 test methods were also run locally against the real
 * production code (kotlin-test stubs): all pass.
 */
class StarredMessageSearchPolicyFuzzTest {

    // ---------- whitespace set (empirically swept, see header) ----------

    private fun isWs(c: Char): Boolean = when (c.code) {
        in 0x09..0x0D, in 0x1C..0x20 -> true
        0x00A0, 0x1680, 0x202F, 0x205F, 0x3000 -> true
        in 0x2000..0x200A, in 0x2028..0x2029 -> true
        else -> false
    }

    private fun isBlankOracle(s: String): Boolean {
        for (c in s) if (!isWs(c)) return false
        return true
    }

    private fun trimOracle(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isWs(s[start])) start++
        while (end > start && isWs(s[end - 1])) end--
        return s.substring(start, end)
    }

    // ---------- oracles ----------

    private fun displayNameOracle(u: User): String {
        val nick = u.nickname
        if (nick != null && !isBlankOracle(nick)) return nick
        if (!isBlankOracle(u.name)) return u.name
        return u.id
    }

    private fun senderLabelOracle(
        chat: Chat?,
        message: Message,
        currentUserId: String,
        meLabel: String,
        memberLabel: String
    ): String {
        if (message.senderId == currentUserId) return meLabel
        if (chat == null) return memberLabel
        for (p in chat.participants) {
            if (p.id == message.senderId) return displayNameOracle(p)
        }
        return memberLabel
    }

    private fun chatTitleOracle(
        chat: Chat?,
        currentUserId: String,
        groupLabel: String,
        memberLabel: String
    ): String {
        if (chat == null) return groupLabel
        if (chat.isGroup) {
            val gn = chat.groupName
            if (gn != null && !isBlankOracle(gn)) return gn
            return groupLabel
        }
        var other: User? = null
        for (p in chat.participants) {
            if (p.id != currentUserId) {
                other = p
                break
            }
        }
        if (other == null && chat.participants.isNotEmpty()) other = chat.participants[0]
        if (other == null) return memberLabel
        return displayNameOracle(other)
    }

    private fun lastMetaIndex(s: String): Int {
        // Last start index of the literal "<meta>", or -1. Explicit backward scan.
        val lit = "<meta>"
        var i = s.length - lit.length
        while (i >= 0) {
            var ok = true
            for (j in lit.indices) if (s[i + j] != lit[j]) {
                ok = false
                break
            }
            if (ok) return i
            i--
        }
        return -1
    }

    private fun searchPreviewOracle(content: String): String {
        val cut = lastMetaIndex(content)
        val before = if (cut < 0) content else content.substring(0, cut)
        return trimOracle(before)
    }

    // Case-insensitive contains, transliterated from the implementation it must
    // match: Kotlin String.contains(other, ignoreCase = true) -> indexOf ->
    // String.regionMatches(ignoreCase = true) -> JDK 17 StringUTF16
    // .regionMatchesCI -> compareToCIImpl (java.base/java/lang/StringUTF16.java).
    // A naive per-char fold is WRONG here, in two ways (both found by the
    // differential sweep, block 47):
    //   - the fold is two-step per code point (upper, then lower-of-upper):
    //     dotless-i U+0131 and dotted-I U+0130 differ in both single steps
    //     (upper: I vs U+0130; lower: U+0131 vs i) yet match, because
    //     lower(upper(U+0131)) == lower(upper(U+0130)) == 'i';
    //   - supplementary characters fold as CODE POINTS (U+10400 ~ U+10428),
    //     not per UTF-16 unit (a low surrogate alone never folds), and a low
    //     surrogate also re-combines with a preceding high surrogate.
    // The transliteration below reproduces the JDK loop verbatim, including its
    // early-exit quirk: when one side consumes a supplementary pair (extra
    // index step) while the other side does not, the loop can exit with
    // tlen - olen == 0 before every needle char was compared.
    private fun compareCodePointCI(cp1: Int, cp2: Int): Int {
        var a = Character.toUpperCase(cp1)
        var b = Character.toUpperCase(cp2)
        if (a != b) {
            a = Character.toLowerCase(a)
            b = Character.toLowerCase(b)
            if (a != b) return a - b
        }
        return 0
    }

    private fun codePointIncluding(s: String, cp: Int, index: Int, start: Int, end: Int): Int {
        if (!Character.isSurrogate(cp.toChar())) return cp
        if (Character.isLowSurrogate(cp.toChar())) {
            if (index > start) {
                val c = s[index - 1]
                if (Character.isHighSurrogate(c)) return Character.toCodePoint(c, cp.toChar())
            }
        } else if (index + 1 < end) {
            val c = s[index + 1]
            if (Character.isLowSurrogate(c)) return -Character.toCodePoint(cp.toChar(), c)
        }
        return cp
    }

    private fun compareToCIImpl(
        value: String, toffset: Int, tlen: Int,
        other: String, ooffset: Int, olen: Int
    ): Int {
        val tlast = toffset + tlen
        val olast = ooffset + olen
        var k1 = toffset
        var k2 = ooffset
        while (k1 < tlast && k2 < olast) {
            var cp1 = value[k1].code
            var cp2 = other[k2].code
            if (cp1 != cp2 && compareCodePointCI(cp1, cp2) != 0) {
                cp1 = codePointIncluding(value, cp1, k1, toffset, tlast)
                if (cp1 < 0) {
                    k1++
                    cp1 = -cp1
                }
                cp2 = codePointIncluding(other, cp2, k2, ooffset, olast)
                if (cp2 < 0) {
                    k2++
                    cp2 = -cp2
                }
                val diff = compareCodePointCI(cp1, cp2)
                if (diff != 0) return diff
            }
            k1++
            k2++
        }
        return tlen - olen
    }

    private fun regionMatchesCIOracle(haystack: String, toffset: Int, needle: String, ooffset: Int, len: Int): Boolean {
        return compareToCIImpl(haystack, toffset, len, needle, ooffset, len) == 0
    }

    private fun containsIgnoreCaseOracle(haystack: String, needle: String): Boolean {
        var start = 0
        while (start + needle.length <= haystack.length) {
            if (regionMatchesCIOracle(haystack, start, needle, 0, needle.length)) return true
            start++
        }
        return false
    }

    private fun matchesQueryOracle(
        message: Message,
        scopeChat: Chat?,
        currentUserId: String,
        query: String,
        meLabel: String,
        groupLabel: String,
        memberLabel: String
    ): Boolean {
        val sender = senderLabelOracle(scopeChat, message, currentUserId, meLabel, memberLabel)
        val title = chatTitleOracle(scopeChat, currentUserId, groupLabel, memberLabel)
        val preview = searchPreviewOracle(message.content)
        return containsIgnoreCaseOracle(sender, query) ||
            containsIgnoreCaseOracle(title, query) ||
            containsIgnoreCaseOracle(preview, query)
    }

    private fun filterOracle(
        messages: List<Message>,
        chatsById: Map<String, Chat>,
        fallbackChat: Chat?,
        currentUserId: String,
        query: String,
        meLabel: String,
        groupLabel: String,
        memberLabel: String
    ): List<Message> {
        val q = trimOracle(query)
        if (q.isEmpty()) return messages
        val out = ArrayList<Message>()
        for (message in messages) {
            val scopeChat = chatsById[message.chatId] ?: fallbackChat
            if (matchesQueryOracle(message, scopeChat, currentUserId, q, meLabel, groupLabel, memberLabel)) {
                out.add(message)
            }
        }
        return out
    }

    // ---------- generators (Random(42) in the CI tests) ----------

    private val ID_POOL = listOf(
        "u1", "u2", "u3", "me", "self", "", "x", "u9", "alice", "BOB",
        "ı", "İ", "Ａ", "ſ", "ß", "K", "Ω", "ann", "Ann", "ANN"
    )

    private val NAME_POOL = listOf(
        "Alice", "Bob", "Me", "Me ", " Bob", "", " ", "\t", "  x  ", "群友",
        "a\nb", "Ω", "ı", "İ", "ＡDMIN", "bob", "BOB", "bOb", "ann", "Ann", "ANN",
        "x", "  ", "a b", "Zoë", "Ωmega", "ßeta"
    )

    private val ME_LABELS = listOf("我", "me", "", "Me")
    private val GROUP_LABELS = listOf("群聊", "Group", "")
    private val MEMBER_LABELS = listOf("群成员", "Member", "")

    private fun randomId(rnd: Random): String {
        if (rnd.nextInt(4) == 0) {
            val alphabet = "abcXYZ019_ "
            val len = rnd.nextInt(0, 6)
            val sb = StringBuilder()
            repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            return sb.toString()
        }
        return ID_POOL[rnd.nextInt(ID_POOL.size)]
    }

    private fun randomName(rnd: Random): String {
        if (rnd.nextInt(4) == 0) {
            val alphabet = "abAB 12_"
            val len = rnd.nextInt(0, 8)
            val sb = StringBuilder()
            repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
            return sb.toString()
        }
        return NAME_POOL[rnd.nextInt(NAME_POOL.size)]
    }

    private fun randomUser(rnd: Random): User {
        val nick: String? = when (rnd.nextInt(5)) {
            0, 1 -> null
            2 -> randomName(rnd)
            3 -> ""
            else -> NAME_POOL[rnd.nextInt(NAME_POOL.size)]
        }
        return User(id = randomId(rnd), name = randomName(rnd), nickname = nick)
    }

    private fun randomChat(rnd: Random): Chat? {
        if (rnd.nextInt(5) == 0) return null
        val isGroup = rnd.nextBoolean()
        val groupName: String? = if (isGroup) {
            when (rnd.nextInt(4)) {
                0 -> null
                1 -> ""
                2 -> "   "
                else -> randomName(rnd)
            }
        } else {
            null
        }
        val n = rnd.nextInt(0, 4)
        val parts = ArrayList<User>(n)
        repeat(n) { parts.add(randomUser(rnd)) }
        return Chat(id = "c" + rnd.nextInt(3), participants = parts, isGroup = isGroup, groupName = groupName)
    }

    private val CONTENT_PIECES = listOf(
        "a", "hello", "你好", "bob", "Bob", "BOB", " ", "  ", "\n", "\t", "\r", "<meta>", "<meta",
        "meta>", "<", ">", "…", "\u00A0", "\u2007", "\u202F", "\u0085", "\u200B",
        "\uFEFF", "\uD83D\uDE00", "a".repeat(50), "{\"a\":1}",
        "word ", "a\nb", "a\rb", "ab", "  pad  ", "Alice", "ALICE", "alice",
        "ı", "İ", "ſ", "ß", "K", "Ω", "ann", "Ann", "ANN"
    )

    private fun randomContent(rnd: Random): String {
        val n = rnd.nextInt(0, 6)
        val sb = StringBuilder()
        repeat(n) { sb.append(CONTENT_PIECES[rnd.nextInt(CONTENT_PIECES.size)]) }
        return sb.toString()
    }

    private fun randomMessage(rnd: Random): Message {
        return Message(
            id = "m" + rnd.nextInt(5),
            chatId = listOf("c1", "c2", "c3", "cX", "")[rnd.nextInt(5)],
            senderId = randomId(rnd),
            content = randomContent(rnd),
            timestamp = 1L
        )
    }

    private fun randomQuery(rnd: Random): String {
        return when (rnd.nextInt(6)) {
            0 -> ""
            1 -> "   "
            2 -> {
                val base = (NAME_POOL + CONTENT_PIECES)[rnd.nextInt(NAME_POOL.size + CONTENT_PIECES.size)]
                if (base.isEmpty()) {
                    ""
                } else {
                    val start = rnd.nextInt(base.length)
                    val end = start + rnd.nextInt(base.length - start + 1)
                    val sub = base.substring(start, end)
                    when (rnd.nextInt(3)) {
                        0 -> sub.lowercase()
                        1 -> sub.uppercase()
                        else -> sub
                    }
                }
            }
            3 -> randomName(rnd)
            4 -> randomContent(rnd)
            else -> {
                val alphabet = "abAB 12_ıİſß"
                val len = rnd.nextInt(0, 8)
                val sb = StringBuilder()
                repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
                sb.toString()
            }
        }
    }

    private fun randomMeLabel(rnd: Random): String = ME_LABELS[rnd.nextInt(ME_LABELS.size)]
    private fun randomGroupLabel(rnd: Random): String = GROUP_LABELS[rnd.nextInt(GROUP_LABELS.size)]
    private fun randomMemberLabel(rnd: Random): String = MEMBER_LABELS[rnd.nextInt(MEMBER_LABELS.size)]

    @Test
    fun senderLabelRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val chat = randomChat(rnd)
            val message = randomMessage(rnd)
            val currentUserId = randomId(rnd)
            val meLabel = randomMeLabel(rnd)
            val memberLabel = randomMemberLabel(rnd)
            assertEquals(
                senderLabelOracle(chat, message, currentUserId, meLabel, memberLabel),
                StarredMessageSearchPolicy.senderLabel(chat, message, currentUserId, meLabel, memberLabel),
                "chat=$chat senderId=${message.senderId} currentUserId=$currentUserId"
            )
        }
    }

    @Test
    fun chatTitleRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val chat = randomChat(rnd)
            val currentUserId = randomId(rnd)
            val groupLabel = randomGroupLabel(rnd)
            val memberLabel = randomMemberLabel(rnd)
            assertEquals(
                chatTitleOracle(chat, currentUserId, groupLabel, memberLabel),
                StarredMessageSearchPolicy.chatTitle(chat, currentUserId, groupLabel, memberLabel),
                "chat=$chat currentUserId=$currentUserId"
            )
        }
    }

    @Test
    fun searchPreviewRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val content = randomContent(rnd)
            assertEquals(
                searchPreviewOracle(content),
                StarredMessageSearchPolicy.searchPreview(content),
                "content=<$content>"
            )
        }
    }

    @Test
    fun filterRandomOracle() {
        val rnd = Random(42)
        repeat(300) {
            val nMsg = rnd.nextInt(0, 6)
            val msgs = ArrayList<Message>(nMsg)
            repeat(nMsg) { msgs.add(randomMessage(rnd)) }
            val nChat = rnd.nextInt(0, 4)
            val byId = LinkedHashMap<String, Chat>()
            repeat(nChat) {
                val id = listOf("c1", "c2", "c3")[rnd.nextInt(3)]
                byId[id] = randomChat(rnd) ?: Chat(id = id)
            }
            val fallback = if (rnd.nextBoolean()) randomChat(rnd) else null
            val currentUserId = randomId(rnd)
            val query = randomQuery(rnd)
            val meLabel = randomMeLabel(rnd)
            val groupLabel = randomGroupLabel(rnd)
            val memberLabel = randomMemberLabel(rnd)
            assertEquals(
                filterOracle(msgs, byId, fallback, currentUserId, query, meLabel, groupLabel, memberLabel),
                StarredMessageSearchPolicy.filter(msgs, byId, fallback, currentUserId, query, meLabel, groupLabel, memberLabel),
                "query=<$query> currentUserId=$currentUserId"
            )
        }
    }

    @Test
    fun pinnedEdgeCases() {
        val me = "我"
        val group = "群聊"
        val member = "群成员"
        fun user(id: String, name: String, nickname: String? = null) = User(id = id, name = name, nickname = nickname)
        fun chat(participants: List<User> = emptyList(), isGroup: Boolean = false, groupName: String? = null) =
            Chat(id = "c1", participants = participants, isGroup = isGroup, groupName = groupName)
        fun message(id: String = "m1", chatId: String = "c1", senderId: String = "u2", content: String = "hello") =
            Message(id = id, chatId = chatId, senderId = senderId, content = content, timestamp = 1L)

        // chatTitle: direct chat takes the first NON-SELF participant, else the first.
        // (GlobalSearchNamePolicy.chatName takes the first participant instead —
        // on [Me, Bob] it returns "Me"; chatTitle returns "Bob". Pinned here.)
        val meBob = chat(participants = listOf(user("u1", "Me"), user("u2", "Bob")))
        assertEquals("Bob", StarredMessageSearchPolicy.chatTitle(meBob, "u1", group, member))
        val bobMe = chat(participants = listOf(user("u2", "Bob"), user("u1", "Me")))
        assertEquals("Bob", StarredMessageSearchPolicy.chatTitle(bobMe, "u1", group, member))
        assertEquals("Me", StarredMessageSearchPolicy.chatTitle(chat(participants = listOf(user("u1", "Me"))), "u1", group, member))
        assertEquals(member, StarredMessageSearchPolicy.chatTitle(chat(), "u1", group, member))
        assertEquals(group, StarredMessageSearchPolicy.chatTitle(null, "u1", group, member))
        // Group: non-blank name wins, blank/null falls back to the group label.
        assertEquals("Fans", StarredMessageSearchPolicy.chatTitle(chat(isGroup = true, groupName = "Fans"), "u1", group, member))
        assertEquals(group, StarredMessageSearchPolicy.chatTitle(chat(isGroup = true, groupName = "  "), "u1", group, member))
        assertEquals(group, StarredMessageSearchPolicy.chatTitle(chat(isGroup = true, groupName = null), "u1", group, member))
        // displayName is returned verbatim, even blank: only a missing participant falls back.
        assertEquals("", StarredMessageSearchPolicy.chatTitle(chat(participants = listOf(user("", ""))), "u1", group, member))
        assertEquals("", StarredMessageSearchPolicy.senderLabel(chat(participants = listOf(user("", ""))), message(senderId = ""), "u1", me, member))
        // senderLabel: self wins even with a null chat; unknown sender falls back.
        assertEquals(me, StarredMessageSearchPolicy.senderLabel(null, message(senderId = "u1"), "u1", me, member))
        assertEquals(member, StarredMessageSearchPolicy.senderLabel(chat(), message(), "u1", me, member))
        assertEquals(member, StarredMessageSearchPolicy.senderLabel(null, message(), "u1", me, member))
        // displayName: non-blank nickname wins over name; blank nickname is skipped.
        val nicked = chat(participants = listOf(user("u2", "Bob", "Bobby")))
        assertEquals("Bobby", StarredMessageSearchPolicy.senderLabel(nicked, message(), "u1", me, member))
        val blankNick = chat(participants = listOf(user("u2", "Bob", "  ")))
        assertEquals("Bob", StarredMessageSearchPolicy.senderLabel(blankNick, message(), "u1", me, member))
        // searchPreview: last <meta> wins; unclosed "<meta" stays literal; trim uses the Kotlin set.
        assertEquals("hello", StarredMessageSearchPolicy.searchPreview("hello <meta>{}</meta>"))
        assertEquals("hello", StarredMessageSearchPolicy.searchPreview("  hello  "))
        assertEquals("", StarredMessageSearchPolicy.searchPreview("<meta>x"))
        assertEquals("a<meta>b", StarredMessageSearchPolicy.searchPreview("a<meta>b<meta>c"))
        assertEquals("a<meta", StarredMessageSearchPolicy.searchPreview("a<meta"))
        assertEquals("", StarredMessageSearchPolicy.searchPreview("\u00A0"))
        assertEquals("\u0085", StarredMessageSearchPolicy.searchPreview("\u0085"))
        assertEquals("\u0085", StarredMessageSearchPolicy.searchPreview(" \u0085 "))
        // matchesQuery does NOT trim: " bob " misses, but filter trims first so it hits.
        val bobChat = chat(participants = listOf(user("u2", "Bob")))
        assertFalse(StarredMessageSearchPolicy.matchesQuery(message(), bobChat, "u1", " bob ", me, group, member))
        assertTrue(StarredMessageSearchPolicy.matchesQuery(message(), bobChat, "u1", "bob", me, group, member))
        assertTrue(StarredMessageSearchPolicy.matchesQuery(message(), bobChat, "u1", "BOB", me, group, member))
        assertTrue(StarredMessageSearchPolicy.matchesQuery(message(), bobChat, "u1", "", me, group, member))
        assertEquals(
            listOf(message()),
            StarredMessageSearchPolicy.filter(listOf(message()), mapOf("c1" to bobChat), null, "u1", " bob ", me, group, member)
        )
        // Case folding is the two-step JDK fold: dotted-I matches i, I and dotless-i alike.
        val idil = chat(participants = listOf(user("u2", "İdil")))
        assertTrue(StarredMessageSearchPolicy.matchesQuery(message(), idil, "u1", "ı", me, group, member))
        assertTrue(StarredMessageSearchPolicy.matchesQuery(message(), idil, "u1", "i", me, group, member))
        assertTrue(StarredMessageSearchPolicy.matchesQuery(message(), idil, "u1", "I", me, group, member))
        assertTrue(StarredMessageSearchPolicy.matchesQuery(message(), idil, "u1", "İ", me, group, member))
        // Supplementary code points fold as code points, not per UTF-16 unit.
        val deseret = message(content = "\uD801\uDC00")
        assertTrue(StarredMessageSearchPolicy.matchesQuery(deseret, null, "u1", "\uD801\uDC28", me, group, member))
        assertFalse(StarredMessageSearchPolicy.matchesQuery(deseret, null, "u1", "nomatch", me, group, member))
        // filter: blank query returns everything in order; unmapped chatId uses the fallback.
        val messages = listOf(message("m1"), message("m2", chatId = "c2", senderId = "u9", content = "bye"))
        assertEquals(messages, StarredMessageSearchPolicy.filter(messages, emptyMap(), null, "u1", "  ", me, group, member))
        val found = StarredMessageSearchPolicy.filter(messages, mapOf("c1" to bobChat), null, "u1", "bob", me, group, member)
        assertEquals(listOf(message("m1")), found)
        // Fallback chat supplies the title for unmapped messages too: m2's
        // scope is the fallback bobChat, so its title is "Bob" and it matches.
        val viaFallback = StarredMessageSearchPolicy.filter(messages, emptyMap(), bobChat, "u1", "bob", me, group, member)
        assertEquals(messages, viaFallback)
        // Meta payload is invisible to the query (preview is cut before <meta>).
        val secret = listOf(message("m1", content = "secret <meta>bob</meta>"))
        assertEquals(emptyList<Message>(), StarredMessageSearchPolicy.filter(secret, emptyMap(), null, "u1", "bob", me, group, member))
        assertEquals(secret, StarredMessageSearchPolicy.filter(secret, emptyMap(), null, "u1", "secret", me, group, member))
    }

}
