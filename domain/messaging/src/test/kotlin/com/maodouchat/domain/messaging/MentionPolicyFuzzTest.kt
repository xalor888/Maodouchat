package com.maodouchat.domain.messaging

import com.maodouchat.core.model.User
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MentionPolicyFuzzTest {

    private companion object {
        private const val ITERATIONS = 200

        private val TEXT_PARTS = listOf(
            "hi ", "@", "@Al", "Alice", "Bob Chen", " ", "\n", "\t", "@AI", "@ai", "@AIX",
            "everyone", "所有人", "all", ",", ".", "!", "?", "x", "a@b", "@@", "@u2",
            "：", "，", "Al", "_1", "me", "u1", "e", "人", "very", "ALL", "EVERYONE",
            "AI ", "summarize", "@Bob", "Chen", "。", "；", ":", ";",
        )
        private val IDS = listOf("u1", "u2", "u3", "me", "", MentionPolicy.EVERYONE_ID, "u9")
        private val NAMES = listOf(
            "Alice", "Bob Chen", "Al", "amy", "Zoe", "", "小明", "Robert", "everyone", "all", "AI",
        )
        private val NICKNAMES: List<String?> = listOf(null, "Bobby", "", "  ", "Al")
        private val FILTERS = listOf(
            "", "al", "Al", "u2", " U ", "eve", "所有人", "人", "all", "ALL", "very",
            "e", "EVERY", "bob", "z", " ", "  ", "ob", "Chen", "小明", "xyz",
        )
        private val DISPLAY_NAMES = listOf(
            "Alice", "Bob Chen", "  ", "", "  Mary  Jane ", "Al_1", "@X", "x", "Al",
        )
        private val BOUNDARY_CHARS = listOf(
            ' ', '\n', '\t', ',', '.', '!', '?', ':', ';', '，', '。', '！', '？', 'x', '@',
        )

        // 以下 oracle 是按 MentionPolicy.kt 的公开规则逐条转写的独立实现：
        // activeQuery 回扫最近未转义 @、@AI 命令抑制；shouldShowPicker 群聊 + @AI 入口抑制；
        // filterCandidates 排除本人/空 id、@everyone 别名、按 displayName 排序、limit 截断；
        // insertMention 替换 query 区间；extractMentionIds 最长名优先 + 边界字符表；
        // shouldHighlightMention 本人/@everyone 且未静音。

        private fun expectedActiveQuery(text: String, cursor: Int): ActiveQuery? {
            if (text.isEmpty()) return null
            val c = cursor.coerceIn(0, text.length)
            if (c == 0) return null
            if (c < text.length && text[c].isWhitespace()) return null
            var i = c - 1
            while (i >= 0) {
                val ch = text[i]
                when {
                    ch == '@' -> {
                        if (!(i == 0 || text[i - 1].isWhitespace())) return null
                        val filter = text.substring(i + 1, c)
                        if (filter.equals("AI", ignoreCase = true)) return null
                        if (filter.startsWith("AI ", ignoreCase = true)) return null
                        return ActiveQuery(atIndex = i, filter = filter)
                    }
                    ch.isWhitespace() -> return null
                    else -> i--
                }
            }
            return null
        }

        private fun expectedShouldShowPicker(text: String, isGroupChat: Boolean, cursor: Int): Boolean {
            if (!isGroupChat) return false
            val trimmed = text.trimStart()
            if (trimmed.startsWith("@AI", ignoreCase = true) &&
                (trimmed.length == 3 || trimmed.getOrNull(3)?.isWhitespace() == true)
            ) {
                val q = expectedActiveQuery(text, cursor) ?: return false
                val token = text.substring(q.atIndex).take(3)
                if (token.equals("@AI", ignoreCase = true)) return false
            }
            return expectedActiveQuery(text, cursor) != null
        }

        private fun expectedFilterCandidates(
            participants: List<User>,
            currentUserId: String,
            filter: String,
            includeEveryone: Boolean,
            limit: Int,
        ): List<Candidate> {
            val q = filter.trim()
            val people = participants
                .asSequence()
                .filter { it.id.isNotBlank() && it.id != currentUserId && it.id != MentionPolicy.EVERYONE_ID }
                .map { Candidate(userId = it.id, displayName = it.displayName.ifBlank { it.id }) }
                .filter { c ->
                    if (q.isEmpty()) true
                    else c.displayName.contains(q, ignoreCase = true) ||
                        c.userId.contains(q, ignoreCase = true)
                }
                .sortedBy { it.displayName.lowercase() }
                .toList()
            val out = ArrayList<Candidate>(limit)
            if (includeEveryone) {
                val everyoneMatch = q.isEmpty() ||
                    "everyone".startsWith(q, ignoreCase = true) ||
                    "所有人".contains(q) ||
                    q.equals("all", ignoreCase = true)
                if (everyoneMatch) {
                    out.add(Candidate(userId = MentionPolicy.EVERYONE_ID, displayName = "everyone", isEveryone = true))
                }
            }
            for (p in people) {
                if (out.size >= limit) break
                out.add(p)
            }
            return out
        }

        private fun expectedInsertMention(
            text: String,
            cursor: Int,
            displayName: String,
            query: ActiveQuery?,
        ): InsertResult {
            val active = query ?: expectedActiveQuery(text, cursor) ?: return InsertResult(text, cursor)
            val label = displayName.trim().ifBlank { return InsertResult(text, cursor) }
            val insertion = "@$label "
            val before = text.substring(0, active.atIndex)
            val after = text.substring(cursor.coerceIn(0, text.length))
            val newText = before + insertion + after
            return InsertResult(text = newText, cursor = before.length + insertion.length)
        }

        private fun expectedBoundaryAfter(rest: String, len: Int): Boolean {
            if (rest.length == len) return true
            if (rest.length < len) return false
            val next = rest[len]
            return next.isWhitespace() || next == ',' || next == '.' || next == '!' ||
                next == '?' || next == ':' || next == ';' || next == '，' || next == '。' ||
                next == '！' || next == '？'
        }

        private fun expectedExtractMentionIds(
            text: String,
            participants: List<User>,
            currentUserId: String,
        ): List<String> {
            if (text.isBlank()) return emptyList()
            val trimmed = text.trimStart()
            if (trimmed.startsWith("@AI", ignoreCase = true) &&
                (trimmed.length == 3 || trimmed.getOrNull(3)?.isWhitespace() == true) &&
                text.count { it == '@' } == 1
            ) {
                return emptyList()
            }
            val ids = linkedSetOf<String>()
            val nameToId = LinkedHashMap<String, String>()
            for (u in participants) {
                if (u.id.isBlank() || u.id == currentUserId) continue
                val name = u.displayName.trim()
                if (name.isNotEmpty()) nameToId.putIfAbsent(name, u.id)
                nameToId.putIfAbsent(u.id, u.id)
            }
            val names = nameToId.keys.sortedByDescending { it.length }
            var i = 0
            while (i < text.length) {
                if (text[i] != '@') {
                    i++
                    continue
                }
                if (!(i == 0 || text[i - 1].isWhitespace())) {
                    i++
                    continue
                }
                val rest = text.substring(i + 1)
                when {
                    rest.startsWith("everyone", ignoreCase = true) && expectedBoundaryAfter(rest, 8) -> {
                        ids.add(MentionPolicy.EVERYONE_ID)
                        i += 1 + 8
                        continue
                    }
                    rest.startsWith("所有人") && expectedBoundaryAfter(rest, 3) -> {
                        ids.add(MentionPolicy.EVERYONE_ID)
                        i += 1 + 3
                        continue
                    }
                    rest.startsWith("all", ignoreCase = true) && expectedBoundaryAfter(rest, 3) -> {
                        ids.add(MentionPolicy.EVERYONE_ID)
                        i += 1 + 3
                        continue
                    }
                }
                var matched: String? = null
                for (name in names) {
                    if (rest.startsWith(name) && expectedBoundaryAfter(rest, name.length)) {
                        matched = name
                        break
                    }
                }
                if (matched != null) {
                    nameToId[matched]?.let { ids.add(it) }
                    i += 1 + matched.length
                } else {
                    i++
                }
            }
            return ids.toList()
        }

        private fun expectedShouldHighlightMention(
            mentionIds: List<String>,
            currentUserId: String,
            notificationsMuted: Boolean,
        ): Boolean {
            if (notificationsMuted || currentUserId.isBlank()) return false
            if (mentionIds.contains(MentionPolicy.EVERYONE_ID)) return true
            return mentionIds.contains(currentUserId)
        }

        private fun randomText(random: Random): String {
            val parts = random.nextInt(0, 8)
            return List(parts) { TEXT_PARTS.random(random) }.joinToString("")
        }

        private fun randomUser(random: Random): User = User(
            id = IDS.random(random),
            name = NAMES.random(random),
            nickname = NICKNAMES.random(random),
        )

        private fun randomParticipants(random: Random): List<User> =
            List(random.nextInt(0, 6)) { randomUser(random) }

        private fun randomCursor(random: Random, text: String): Int =
            random.nextInt(-5, text.length + 6)
    }

    @Test
    fun `activeQuery matches oracle on random text and cursor`() {
        val random = Random(0xA2_2030)
        repeat(ITERATIONS) { i ->
            val text = randomText(random)
            val cursor = randomCursor(random, text)
            val first = MentionPolicy.activeQuery(text, cursor)
            val second = MentionPolicy.activeQuery(text, cursor)
            assertEquals(first, second, "fuzz 迭代 #$i：同一输入两次判定必须相同")
            assertEquals(
                expectedActiveQuery(text, cursor), first,
                "fuzz 迭代 #$i：activeQuery 必须与规则一致：text=<$text> cursor=$cursor",
            )
            if (first != null) {
                assertEquals('@', text[first.atIndex], "fuzz 迭代 #$i：query 起点必须是 @")
                assertTrue(first.filter.none { it.isWhitespace() }, "fuzz 迭代 #$i：filter 内不得含空白")
                val c = cursor.coerceIn(0, text.length)
                assertEquals(
                    text.substring(first.atIndex + 1, c), first.filter,
                    "fuzz 迭代 #$i：filter 必须是 @ 后到光标前的子串",
                )
            }
        }
    }

    @Test
    fun `shouldShowPicker matches oracle on random text and cursor`() {
        val random = Random(0xA2_2031)
        repeat(ITERATIONS) { i ->
            val text = randomText(random)
            val cursor = randomCursor(random, text)
            val isGroup = random.nextBoolean()
            assertEquals(
                expectedShouldShowPicker(text, isGroup, cursor),
                MentionPolicy.shouldShowPicker(text, isGroup, cursor),
                "fuzz 迭代 #$i：shouldShowPicker 必须与规则一致：text=<$text> group=$isGroup cursor=$cursor",
            )
            if (!isGroup) {
                assertFalse(
                    MentionPolicy.shouldShowPicker(text, isGroup, cursor),
                    "fuzz 迭代 #$i：非群聊永不弹选择器",
                )
            }
        }
    }

    @Test
    fun `filterCandidates matches oracle on random participants and filters`() {
        val random = Random(0xA2_2032)
        repeat(ITERATIONS) { i ->
            val participants = randomParticipants(random)
            val currentUserId = IDS.random(random)
            val filter = FILTERS.random(random)
            val includeEveryone = random.nextBoolean()
            val limit = random.nextInt(0, 5)
            val expected = expectedFilterCandidates(participants, currentUserId, filter, includeEveryone, limit)
            val actual = MentionPolicy.filterCandidates(participants, currentUserId, filter, includeEveryone, limit)
            assertEquals(
                expected, actual,
                "fuzz 迭代 #$i：filterCandidates 必须与规则一致：filter=<$filter> limit=$limit everyone=$includeEveryone",
            )
            // @everyone 项固定排第一（不参与排序），其余候选按 displayName 排序。
            val peopleNames = actual.filter { !it.isEveryone }.map { it.displayName.lowercase() }
            assertEquals(peopleNames.sorted(), peopleNames, "fuzz 迭代 #$i：候选必须按 displayName 排序")
            // @everyone 项固定带 EVERYONE_ID；currentUserId 恰好抽到它时不算「含本人」。
            assertTrue(
                actual.none { it.userId == currentUserId && !it.isEveryone },
                "fuzz 迭代 #$i：不得含本人",
            )
            assertTrue(actual.none { it.userId.isBlank() }, "fuzz 迭代 #$i：不得含空 id")
            assertTrue(actual.count { it.isEveryone } <= 1, "fuzz 迭代 #$i：@everyone 至多一项")
            if (actual.any { it.isEveryone }) {
                assertTrue(actual.first().isEveryone, "fuzz 迭代 #$i：@everyone 必须排第一")
            }
            val cap = if (includeEveryone) maxOf(limit, 1) else limit
            assertTrue(actual.size <= cap, "fuzz 迭代 #$i：结果不得超过上限 $cap")
        }
    }

    @Test
    fun `insertMention matches oracle on random text cursor and name`() {
        val random = Random(0xA2_2033)
        repeat(ITERATIONS) { i ->
            val text = randomText(random)
            val cursor = randomCursor(random, text)
            val displayName = DISPLAY_NAMES.random(random)
            val query = when (random.nextInt(0, 3)) {
                0 -> null
                1 -> MentionPolicy.activeQuery(text, cursor)
                else -> ActiveQuery(random.nextInt(0, text.length + 1), "Al")
            }
            assertEquals(
                expectedInsertMention(text, cursor, displayName, query),
                MentionPolicy.insertMention(text, cursor, displayName, query),
                "fuzz 迭代 #$i：insertMention 必须与规则一致：text=<$text> cursor=$cursor name=<$displayName> query=$query",
            )
        }
    }

    @Test
    fun `extractMentionIds matches oracle on random text and participants`() {
        val random = Random(0xA2_2034)
        repeat(ITERATIONS) { i ->
            val text = randomText(random)
            val participants = randomParticipants(random)
            val currentUserId = IDS.random(random)
            val expected = expectedExtractMentionIds(text, participants, currentUserId)
            val actual = MentionPolicy.extractMentionIds(text, participants, currentUserId)
            assertEquals(
                expected, actual,
                "fuzz 迭代 #$i：extractMentionIds 必须与规则一致：text=<$text>",
            )
            assertEquals(actual.distinct(), actual, "fuzz 迭代 #$i：结果不得重复")
            val known = participants.map { it.id }.toSet() + MentionPolicy.EVERYONE_ID
            assertTrue(actual.all { it in known }, "fuzz 迭代 #$i：结果只能是已知 id 或 @everyone")
            if (currentUserId != MentionPolicy.EVERYONE_ID) {
                assertFalse(currentUserId in actual, "fuzz 迭代 #$i：不得含本人 id")
            }
        }
    }

    @Test
    fun `extractMentionIds ai-only command with single at sign yields nothing`() {
        val random = Random(0xA2_2035)
        val participants = listOf(
            User(id = "u1", name = "Alice"),
            User(id = "u2", name = "Bob Chen"),
        )
        repeat(ITERATIONS) { i ->
            val suffix = listOf("", " x", " summarize", " 请总结", "\nnext").random(random)
            val prefix = listOf("", "  ").random(random)
            val head = listOf("@AI", "@ai", "@Ai").random(random)
            val text = "$prefix$head$suffix"
            if (text.count { it == '@' } != 1) return@repeat
            assertEquals(
                emptyList<String>(), MentionPolicy.extractMentionIds(text, participants, "me"),
                "fuzz 迭代 #$i：整段仅为 @AI 命令时不得当成员提及：<$text>",
            )
        }
    }

    @Test
    fun `shouldHighlightMention and displayToken match oracle on random inputs`() {
        val random = Random(0xA2_2036)
        repeat(ITERATIONS) { i ->
            val pool = listOf("u1", "u2", "me", MentionPolicy.EVERYONE_ID, "u9")
            val mentionIds = List(random.nextInt(0, 4)) { pool.random(random) }
            val currentUserId = IDS.random(random)
            val muted = random.nextBoolean()
            assertEquals(
                expectedShouldHighlightMention(mentionIds, currentUserId, muted),
                MentionPolicy.shouldHighlightMention(mentionIds, currentUserId, muted),
                "fuzz 迭代 #$i：shouldHighlightMention 必须与规则一致",
            )
            val user = randomUser(random)
            assertEquals(
                "@${user.displayName.ifBlank { user.id }}",
                MentionPolicy.displayTokenForUser(user),
                "fuzz 迭代 #$i：displayToken 必须是 @ + displayName",
            )
        }
    }

    @Test
    fun `boundary characters after a mention behave as the boundary table`() {
        // 边界字符表：空白 , . ! ? : ; ， 。 ！ ？ ——其余字符（字母/数字/@）不断开提及。
        val participants = listOf(User(id = "u1", name = "Alice"))
        for (ch in BOUNDARY_CHARS) {
            val text = "hi @Alice${ch}ok"
            val expected = if (ch.isWhitespace() || ch in ",.!?:;，。！？") listOf("u1") else emptyList()
            assertEquals(
                expected, MentionPolicy.extractMentionIds(text, participants, "me"),
                "边界字符 <$ch> 后提及判定必须与边界表一致",
            )
        }
    }
}
