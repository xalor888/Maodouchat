package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ChatFolderPolicy] (checklist line 931, 43rd block).
 * Test-only, zero production changes.
 *
 * The oracle reimplements all 14 functions from scratch (explicit loops instead of
 * the production's map/filter chains, a swap map for moveFolder, an explicit rebuild
 * for reorderFolder). A Python mini-model differentially checks this oracle against
 * an independent reimplementation on 5000 random inputs (0 diffs), including
 * duplicate ids, duplicate sortOrders, negative orders and Int extremes.
 *
 * Notes: random folder names are BMP-only, so take(48) counts the same in UTF-16
 * units and code points; createFolder is always called with an explicit id because
 * the default id is UUID.randomUUID(). normalizeName was previously borrowed by the
 * in-flight ChatFolderJsonPolicyFuzzTest as a codec-oracle aid; the 13 list-transform
 * functions here are fuzz targets for the first time.
 */
class ChatFolderPolicyFuzzTest {

    private fun compareCodePointIgnoreCase(c1: Int, c2: Int): Boolean {
        if (c1 == c2) return true
        val u1 = Character.toUpperCase(c1)
        val u2 = Character.toUpperCase(c2)
        if (u1 == u2) return true
        return Character.toLowerCase(u1) == Character.toLowerCase(u2)
    }

    // Case-insensitive equality: equal UTF-16 length, then code-point comparison.
    // Exact here because every generated name is BMP-only.
    private fun equalsIgnoreCase(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        if (a.codePointCount(0, a.length) != b.codePointCount(0, b.length)) return false
        var ai = 0
        var bi = 0
        while (ai < a.length) {
            val ac = a.codePointAt(ai)
            val bc = b.codePointAt(bi)
            if (!compareCodePointIgnoreCase(ac, bc)) return false
            ai += Character.charCount(ac)
            bi += Character.charCount(bc)
        }
        return true
    }

    private fun isAsciiWhitespace(ch: Char): Boolean =
        ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000B' || ch == '\u000C' || ch == '\r'

    // normalizeName, reimplemented by hand: trim (chars <= ' '), collapse each run of
    // ASCII whitespace to one space (Java \s is ASCII-only), take the first 48 chars.
    private fun oracleNormalizeName(raw: String?): String {
        val s = raw ?: ""
        var start = 0
        var end = s.length
        while (start < end && s[start] <= ' ') start++
        while (end > start && s[end - 1] <= ' ') end--
        val out = StringBuilder()
        var i = start
        while (i < end) {
            if (isAsciiWhitespace(s[i])) {
                out.append(' ')
                while (i < end && isAsciiWhitespace(s[i])) i++
            } else {
                out.append(s[i])
                i++
            }
        }
        return out.toString().take(ChatFolderPolicy.MAX_NAME_LEN)
    }

    private fun oracleIsSystemFilter(folderId: String?): Boolean =
        folderId == ChatFolderPolicy.SYSTEM_GROUPS_ID ||
            folderId == ChatFolderPolicy.SYSTEM_DIRECT_ID ||
            folderId == ChatFolderPolicy.SYSTEM_UNREAD_ID ||
            folderId == ChatFolderPolicy.SYSTEM_SECRET_ID ||
            folderId == ChatFolderPolicy.SYSTEM_LOCKED_ID

    private fun oracleCreateFolder(existing: List<ChatFolder>, name: String, id: String): List<ChatFolder>? {
        if (existing.size >= ChatFolderPolicy.MAX_FOLDERS) return null
        val cleaned = oracleNormalizeName(name)
        if (cleaned.isEmpty()) return null
        if (existing.any { equalsIgnoreCase(it.name, cleaned) }) return null
        var maxOrder: Int? = null
        for (f in existing) {
            if (maxOrder == null || f.sortOrder > maxOrder) maxOrder = f.sortOrder
        }
        val nextOrder = (maxOrder ?: -1) + 1
        return (existing + ChatFolder(id = id, name = cleaned, sortOrder = nextOrder))
            .sortedBy { it.sortOrder }
    }

    private fun oracleRenameFolder(existing: List<ChatFolder>, folderId: String, name: String): List<ChatFolder>? {
        val cleaned = oracleNormalizeName(name)
        if (cleaned.isEmpty()) return null
        var found = false
        for (f in existing) {
            if (f.id == folderId) {
                found = true
            } else if (equalsIgnoreCase(f.name, cleaned)) {
                return null
            }
        }
        if (!found) return null
        return existing.map { if (it.id == folderId) it.copy(name = cleaned) else it }
    }

    private fun oracleDeleteFolder(existing: List<ChatFolder>, folderId: String): List<ChatFolder> {
        val out = mutableListOf<ChatFolder>()
        for (f in existing) if (f.id != folderId) out.add(f)
        return out
    }

    private fun oracleMoveFolder(existing: List<ChatFolder>, folderId: String, delta: Int): List<ChatFolder>? {
        val sorted = existing.sortedBy { it.sortOrder }
        val index = sorted.indexOfFirst { it.id == folderId }
        if (index < 0) return null
        val target = index + delta
        if (target < 0 || target >= sorted.size) return null
        val a = sorted[index]
        val b = sorted[target]
        // Swap by id, first branch wins when two folders share an id.
        val swapped = mutableMapOf<String, Int>()
        swapped[a.id] = b.sortOrder
        if (b.id !in swapped) swapped[b.id] = a.sortOrder
        return sorted.map { it.copy(sortOrder = swapped[it.id] ?: it.sortOrder) }
            .sortedBy { it.sortOrder }
    }

    private fun oracleReorderFolder(existing: List<ChatFolder>, folderId: String, targetIndex: Int): List<ChatFolder>? {
        val sorted = existing.sortedBy { it.sortOrder }
        val from = sorted.indexOfFirst { it.id == folderId }
        if (from < 0) return null
        val to = targetIndex.coerceIn(0, sorted.size - 1)
        // Moving onto its own slot only sorts; the old orders are kept.
        if (from == to) return sorted
        val moved = sorted[from]
        val rest = sorted.filterIndexed { i, _ -> i != from }
        val rebuilt = mutableListOf<ChatFolder>()
        for (i in rest.indices) {
            if (i == to) rebuilt.add(moved)
            rebuilt.add(rest[i])
        }
        if (to == rest.size) rebuilt.add(moved)
        return rebuilt.mapIndexed { i, folder -> folder.copy(sortOrder = i) }
    }

    private fun oracleSetChatInFolder(
        existing: List<ChatFolder>,
        folderId: String,
        chatId: String,
        included: Boolean
    ): List<ChatFolder> {
        if (chatId.isBlank()) return existing
        return existing.map { folder ->
            if (folder.id != folderId) folder
            else {
                val ids = folder.chatIds.toMutableList()
                if (included) {
                    if (chatId !in ids) ids += chatId
                } else {
                    // MutableList.remove drops only the first occurrence.
                    val at = ids.indexOf(chatId)
                    if (at >= 0) ids.removeAt(at)
                }
                folder.copy(chatIds = ids)
            }
        }
    }

    private fun oracleMoveChatToFolder(
        existing: List<ChatFolder>,
        chatId: String,
        targetFolderId: String?
    ): List<ChatFolder> {
        if (chatId.isBlank()) return existing
        return existing.map { folder ->
            val without = mutableListOf<String>()
            for (c in folder.chatIds) if (c != chatId) without.add(c)
            if (targetFolderId != null && folder.id == targetFolderId) without.add(chatId)
            folder.copy(chatIds = without)
        }
    }

    private fun oracleFolderOfChat(existing: List<ChatFolder>, chatId: String): ChatFolder? {
        for (f in existing) if (chatId in f.chatIds) return f
        return null
    }

    private fun oracleFilterChatIds(folder: ChatFolder?, allChatIds: List<String>): List<String> {
        if (folder == null) return allChatIds
        val out = mutableListOf<String>()
        for (c in allChatIds) if (c in folder.chatIds) out.add(c)
        return out
    }

    private fun oracleUnreadInFolder(folder: ChatFolder, unreadByChatId: Map<String, Int>): Int {
        var total = 0
        for (chatId in folder.chatIds) {
            val v = unreadByChatId[chatId] ?: 0
            total += if (v < 0) 0 else v
        }
        return total
    }

    private val folderIds = listOf("f0", "f1", "f2", "a", "b", "folder_x", "system:groups", "")
    private val names = listOf(
        "Work", "work", "WORK", "Family", "family", "a b", "  New  Folder ",
        "", "   ", "x".repeat(60), "a   b\tc", "Ab", "aB", "ß", "SS", "  WORK  ",
        "x".repeat(48), "x".repeat(49), "Ångström", "ÅNGSTRÖM", "c1", "  "
    )
    private val chatIds = listOf("c1", "c2", "c3", "")
    private val orders = listOf(-2, -1, 0, 1, 2, 3, 5, 6)
    private val raws = names + listOf(
        "\u00A0a\u00A0", "a\u0085b", "a\u2007b", "\t a \n b \r", "x".repeat(100),
        "  a   b\tc  ", "\u200B", " a\u00A0 b ", null
    )

    private fun randomFolder(rnd: Random): ChatFolder =
        ChatFolder(
            id = folderIds.random(rnd),
            name = names.random(rnd),
            chatIds = List(rnd.nextInt(0, 5)) { chatIds.random(rnd) },
            sortOrder = if (rnd.nextInt(10) < 9) orders.random(rnd) else listOf(Int.MAX_VALUE, Int.MIN_VALUE).random(rnd)
        )

    private fun randomFolders(rnd: Random): List<ChatFolder> =
        List(rnd.nextInt(0, 7)) { randomFolder(rnd) }

    @Test
    fun normalizeNameRandomOracle() {
        val rnd = Random(47)
        repeat(300) {
            val raw = raws.random(rnd)
            val got = ChatFolderPolicy.normalizeName(raw)
            assertEquals(oracleNormalizeName(raw), got, "raw=<$raw>")
            assertTrue(got.length <= ChatFolderPolicy.MAX_NAME_LEN)
            assertTrue("  " !in got, "no double space survives: <$got>")
        }
    }

    @Test
    fun createRenameDeleteRandomOracle() {
        val rnd = Random(47)
        repeat(200) {
            val folders = randomFolders(rnd)
            val op = rnd.nextInt(3)
            when (op) {
                0 -> {
                    val name = raws.random(rnd) ?: ""
                    val id = listOf("n1", "f0", "folder_new", "").random(rnd)
                    val got = ChatFolderPolicy.createFolder(folders, name, id)
                    assertEquals(oracleCreateFolder(folders, name, id), got, "folders=$folders name=<$name> id=<$id>")
                    if (got != null) {
                        assertEquals(
                            (folders.map { it.id } + id).sorted(),
                            got.map { it.id }.sorted(),
                            "create only adds the new id"
                        )
                    }
                }
                1 -> {
                    val fid = (folderIds + "missing").random(rnd)
                    val name = raws.random(rnd) ?: ""
                    assertEquals(
                        oracleRenameFolder(folders, fid, name),
                        ChatFolderPolicy.renameFolder(folders, fid, name),
                        "folders=$folders fid=<$fid> name=<$name>"
                    )
                }
                else -> {
                    val fid = (folderIds + "missing").random(rnd)
                    assertEquals(
                        oracleDeleteFolder(folders, fid),
                        ChatFolderPolicy.deleteFolder(folders, fid),
                        "folders=$folders fid=<$fid>"
                    )
                }
            }
        }
    }

    @Test
    fun moveReorderRandomOracle() {
        val rnd = Random(47)
        repeat(300) {
            val folders = randomFolders(rnd)
            val fid = (folderIds + "missing").random(rnd)
            val delta = listOf(-2, -1, 0, 1, 2, Int.MAX_VALUE, Int.MIN_VALUE, 99, -99).random(rnd)
            assertEquals(
                oracleMoveFolder(folders, fid, delta),
                ChatFolderPolicy.moveFolder(folders, fid, delta),
                "move folders=$folders fid=<$fid> delta=$delta"
            )
            val target = listOf(-5, -1, 0, 1, 2, 3, 99, folders.size, folders.size + 1).random(rnd)
            val got = ChatFolderPolicy.reorderFolder(folders, fid, target)
            assertEquals(oracleReorderFolder(folders, fid, target), got, "reorder folders=$folders fid=<$fid> target=$target")
            if (got != null) {
                val sorted = folders.sortedBy { it.sortOrder }
                val from = sorted.indexOfFirst { it.id == fid }
                val to = target.coerceIn(0, sorted.size - 1)
                if (from == to) {
                    // Moving onto its own slot keeps the old orders.
                    assertEquals(sorted.map { it.sortOrder }, got.map { it.sortOrder })
                } else {
                    assertEquals((0 until got.size).toList(), got.map { it.sortOrder })
                }
            }
        }
    }

    @Test
    fun setChatAndMoveChatRandomOracle() {
        val rnd = Random(47)
        repeat(300) {
            val folders = randomFolders(rnd)
            val fid = (folderIds + "missing").random(rnd)
            val chatId = (chatIds + listOf("c9", " ", "  ", "c1")).random(rnd)
            val included = rnd.nextBoolean()
            assertEquals(
                oracleSetChatInFolder(folders, fid, chatId, included),
                ChatFolderPolicy.setChatInFolder(folders, fid, chatId, included),
                "setChat folders=$folders fid=<$fid> chat=<$chatId> included=$included"
            )
            val target = (folderIds + listOf<String?>(null, "missing")).random(rnd)
            val got = ChatFolderPolicy.moveChatToFolder(folders, chatId, target)
            assertEquals(oracleMoveChatToFolder(folders, chatId, target), got, "moveChat folders=$folders chat=<$chatId> target=<$target>")
            // Single homing: after the move the chat lives in exactly the target
            // folders (one copy each), nowhere else.
            if (chatId.isNotBlank()) {
                val total = got.sumOf { it.chatIds.count { c -> c == chatId } }
                val expected = if (target == null) 0 else folders.count { it.id == target }
                assertEquals(expected, total, "chat=<$chatId> target=<$target> got=$got")
            }
        }
    }

    @Test
    fun lookupAndUnreadRandomOracle() {
        val rnd = Random(47)
        repeat(300) {
            val folders = randomFolders(rnd)
            val chatId = (chatIds + listOf("c9", " ", "c1")).random(rnd)
            assertEquals(
                oracleFolderOfChat(folders, chatId),
                ChatFolderPolicy.folderOfChat(folders, chatId),
                "folders=$folders chat=<$chatId>"
            )
            val allIds = List(rnd.nextInt(0, 6)) { (chatIds + "c9").random(rnd) }
            val folder = if (folders.isNotEmpty() && rnd.nextInt(10) < 7) folders.random(rnd) else null
            assertEquals(
                oracleFilterChatIds(folder, allIds),
                ChatFolderPolicy.filterChatIds(folder, allIds),
                "folder=$folder all=$allIds"
            )
            val unread = chatIds.associateWith { listOf(-5, 0, 1, 3, 10, Int.MAX_VALUE, Int.MIN_VALUE).random(rnd) }
            val unreadSmall = chatIds.associateWith { listOf(-5, 0, 1, 3, 10).random(rnd) }
            if (folders.isNotEmpty()) {
                val uf = folders.random(rnd)
                assertEquals(oracleUnreadInFolder(uf, unread), ChatFolderPolicy.unreadInFolder(uf, unread), "folder=$uf unread=$unread")
                val small = ChatFolderPolicy.unreadInFolder(uf, unreadSmall)
                assertEquals(oracleUnreadInFolder(uf, unreadSmall), small)
                assertTrue(small >= 0, "unread sum never goes negative on small inputs")
            }
            val fid = (folderIds + listOf<String?>(null, "missing", "system:unknown", "")).random(rnd)
            assertEquals(oracleIsSystemFilter(fid), ChatFolderPolicy.isSystemFilter(fid), "fid=<$fid>")
            val n = listOf(0, 1, -3).random(rnd)
            val marked = rnd.nextBoolean()
            assertEquals(n > 0 || marked, ChatFolderPolicy.isUnreadChat(n, marked))
            val count = listOf(0, 1, 27, 28, 29, 100).random(rnd)
            assertEquals(count < ChatFolderPolicy.MAX_FOLDERS, ChatFolderPolicy.canCreateMore(count))
        }
    }

    @Test
    fun operationSequenceRandomOracle() {
        val rnd = Random(47)
        repeat(150) {
            var prod: List<ChatFolder>? = randomFolders(rnd)
            var oracle: List<ChatFolder>? = prod
            seq@ for (step in 0 until rnd.nextInt(1, 5)) {
                val op = rnd.nextInt(6)
                val fid = (folderIds + "missing").random(rnd)
                val name = names.random(rnd)
                val delta = listOf(-1, 1, 2).random(rnd)
                val target = listOf(0, 1, 2).random(rnd)
                val chat = chatIds.random(rnd)
                val tgt = (folderIds + listOf<String?>(null)).random(rnd)
                // Same drawn parameters go to both implementations.
                prod = when (op) {
                    0 -> prod?.let { ChatFolderPolicy.createFolder(it, name, "s1") }
                    1 -> prod?.let { ChatFolderPolicy.renameFolder(it, fid, name) }
                    2 -> prod?.let { ChatFolderPolicy.deleteFolder(it, fid) }
                    3 -> prod?.let { ChatFolderPolicy.moveFolder(it, fid, delta) }
                    4 -> prod?.let { ChatFolderPolicy.reorderFolder(it, fid, target) }
                    else -> prod?.let { ChatFolderPolicy.moveChatToFolder(it, chat, tgt) }
                }
                oracle = when (op) {
                    0 -> oracle?.let { oracleCreateFolder(it, name, "s1") }
                    1 -> oracle?.let { oracleRenameFolder(it, fid, name) }
                    2 -> oracle?.let { oracleDeleteFolder(it, fid) }
                    3 -> oracle?.let { oracleMoveFolder(it, fid, delta) }
                    4 -> oracle?.let { oracleReorderFolder(it, fid, target) }
                    else -> oracle?.let { oracleMoveChatToFolder(it, chat, tgt) }
                }
                if (prod == null || oracle == null) {
                    assertEquals(oracle, prod, "null must agree after op=$op")
                    break@seq
                }
                assertEquals(oracle, prod, "sequence diverged after op=$op")
            }
        }
    }

    @Test
    fun pinnedFolderBoundaries() {
        // createFolder: full house, blank name, case-insensitive duplicate.
        val full = (1..ChatFolderPolicy.MAX_FOLDERS).map { ChatFolder(id = "f$it", name = "n$it") }
        assertNull(ChatFolderPolicy.createFolder(full, "one-more", "x1"))
        assertNull(ChatFolderPolicy.createFolder(emptyList(), "   ", "x1"))
        assertNull(ChatFolderPolicy.createFolder(listOf(ChatFolder(id = "f1", name = "Work")), "work", "x1"))
        assertNull(ChatFolderPolicy.createFolder(listOf(ChatFolder(id = "f1", name = "Work")), "  WORK  ", "x1"))
        // Normal create normalizes the name and takes max order + 1.
        val created = ChatFolderPolicy.createFolder(
            listOf(ChatFolder(id = "b", name = "B", sortOrder = 5), ChatFolder(id = "a", name = "A", sortOrder = 2)),
            "  New  Folder ", "n1"
        )
        assertEquals(listOf("a", "b", "n1"), created?.map { it.id })
        assertEquals(listOf(2, 5, 6), created?.map { it.sortOrder })
        assertEquals("New Folder", created?.last()?.name)
        // renameFolder: other folders' names are off-limits, its own is fine.
        val folders = listOf(ChatFolder(id = "f1", name = "Work"), ChatFolder(id = "f2", name = "Family"))
        assertNull(ChatFolderPolicy.renameFolder(folders, "f2", "work"))
        assertEquals("family", ChatFolderPolicy.renameFolder(folders, "f2", "family")?.first { it.id == "f2" }?.name)
        assertNull(ChatFolderPolicy.renameFolder(folders, "missing", "New"))
        assertNull(ChatFolderPolicy.renameFolder(folders, "f1", "  "))
        // moveFolder swaps sortOrders; out of range and unknown ids return null.
        val abc = listOf(ChatFolder(id = "a", name = "A", sortOrder = 0), ChatFolder(id = "b", name = "B", sortOrder = 1), ChatFolder(id = "c", name = "C", sortOrder = 2))
        assertEquals(listOf("b", "a", "c"), ChatFolderPolicy.moveFolder(abc, "b", -1)?.map { it.id })
        assertEquals(listOf("c", "b", "a"), ChatFolderPolicy.moveFolder(abc, "a", 2)?.map { it.id })
        assertEquals(listOf("a", "b", "c"), ChatFolderPolicy.moveFolder(abc, "b", 0)?.map { it.id })
        assertNull(ChatFolderPolicy.moveFolder(abc, "a", -1))
        assertNull(ChatFolderPolicy.moveFolder(abc, "c", 1))
        assertNull(ChatFolderPolicy.moveFolder(abc, "nope", 1))
        // reorderFolder inserts and renumbers; out-of-range targets clamp; moving
        // onto its own slot keeps the old orders.
        val abcd = listOf(ChatFolder(id = "a", name = "A", sortOrder = 0), ChatFolder(id = "b", name = "B", sortOrder = 1), ChatFolder(id = "c", name = "C", sortOrder = 2), ChatFolder(id = "d", name = "D", sortOrder = 3))
        val reordered = ChatFolderPolicy.reorderFolder(abcd, "a", 2)
        assertEquals(listOf("b", "c", "a", "d"), reordered?.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), reordered?.map { it.sortOrder })
        assertEquals(listOf("b", "c", "d", "a"), ChatFolderPolicy.reorderFolder(abcd, "a", 99)?.map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), ChatFolderPolicy.reorderFolder(abcd, "d", -5)?.map { it.id })
        assertNull(ChatFolderPolicy.reorderFolder(abcd, "nope", 1))
        val gapped = listOf(ChatFolder(id = "b", name = "B", sortOrder = 5), ChatFolder(id = "a", name = "A", sortOrder = 3))
        assertEquals(listOf(3, 5), ChatFolderPolicy.reorderFolder(gapped, "a", 0)?.map { it.sortOrder })
        // Single homing: the chat leaves every folder and lands once in the target.
        val withChats = listOf(
            ChatFolder(id = "f1", name = "F1", chatIds = listOf("c1", "c2"), sortOrder = 0),
            ChatFolder(id = "f2", name = "F2", chatIds = listOf("c1"), sortOrder = 1)
        )
        val moved = ChatFolderPolicy.moveChatToFolder(withChats, "c1", "f2")
        assertEquals(listOf("c2"), moved.first { it.id == "f1" }.chatIds)
        assertEquals(listOf("c1"), moved.first { it.id == "f2" }.chatIds)
        assertEquals(1, moved.count { "c1" in it.chatIds })
        assertTrue(ChatFolderPolicy.moveChatToFolder(withChats, "c1", null).none { "c1" in it.chatIds })
        assertEquals(withChats, ChatFolderPolicy.moveChatToFolder(withChats, "  ", "f2"))
        // setChatInFolder adds once, removes only the first copy, ignores blanks.
        val one = listOf(ChatFolder(id = "f1", name = "F1", chatIds = listOf("c1", "c1"), sortOrder = 0))
        assertEquals(listOf("c1"), ChatFolderPolicy.setChatInFolder(one, "f1", "c1", true).first().chatIds)
        assertEquals(listOf("c1"), ChatFolderPolicy.setChatInFolder(one, "f1", "c1", false).first().chatIds)
        assertEquals(one, ChatFolderPolicy.setChatInFolder(one, "f1", " ", true))
        // Lookups and unread sums.
        assertEquals("f1", ChatFolderPolicy.folderOfChat(withChats, "c2")?.id)
        assertNull(ChatFolderPolicy.folderOfChat(withChats, "c9"))
        assertEquals(listOf("c2", "c1"), ChatFolderPolicy.filterChatIds(withChats[1].let { it.copy(chatIds = listOf("c1", "c2")) }, listOf("c2", "c1", "c3")))
        assertEquals(listOf("c1", "c2", "c3"), ChatFolderPolicy.filterChatIds(null, listOf("c1", "c2", "c3")))
        val unreadFolder = ChatFolder(id = "f0", name = "A", chatIds = listOf("c1", "c2", "c3"), sortOrder = 0)
        assertEquals(2, ChatFolderPolicy.unreadInFolder(unreadFolder, mapOf("c1" to 2, "c3" to -4)))
        assertEquals(0, ChatFolderPolicy.unreadInFolder(unreadFolder, mapOf("c1" to -5)))
        // normalizeName: trim, collapse, cap at 48; take(48) counts UTF-16 units,
        // so a trailing astral char is cut to its lone high surrogate.
        assertEquals("a b c", ChatFolderPolicy.normalizeName("  a   b\tc  "))
        assertEquals("", ChatFolderPolicy.normalizeName(null))
        assertEquals("", ChatFolderPolicy.normalizeName("   \t\n "))
        assertEquals(48, ChatFolderPolicy.normalizeName("x".repeat(60)).length)
        assertEquals("x".repeat(47) + "\uD83D", ChatFolderPolicy.normalizeName("x".repeat(47) + "\uD83D\uDE00"))
        // System filters, capacity and unread flags.
        listOf(
            ChatFolderPolicy.SYSTEM_GROUPS_ID, ChatFolderPolicy.SYSTEM_DIRECT_ID,
            ChatFolderPolicy.SYSTEM_UNREAD_ID, ChatFolderPolicy.SYSTEM_SECRET_ID,
            ChatFolderPolicy.SYSTEM_LOCKED_ID
        ).forEach { assertTrue(ChatFolderPolicy.isSystemFilter(it)) }
        assertEquals(false, ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.ALL_ID))
        assertEquals(false, ChatFolderPolicy.isSystemFilter(null))
        assertEquals(false, ChatFolderPolicy.isSystemFilter("system:unknown"))
        assertEquals(false, ChatFolderPolicy.isSystemFilter(""))
        assertTrue(ChatFolderPolicy.canCreateMore(ChatFolderPolicy.MAX_FOLDERS - 1))
        assertEquals(false, ChatFolderPolicy.canCreateMore(ChatFolderPolicy.MAX_FOLDERS))
        assertTrue(ChatFolderPolicy.isUnreadChat(1, false))
        assertTrue(ChatFolderPolicy.isUnreadChat(0, true))
        assertEquals(false, ChatFolderPolicy.isUnreadChat(0, false))
        assertEquals(false, ChatFolderPolicy.isUnreadChat(-3, false))
        assertTrue(ChatFolderPolicy.isUnreadChat(-3, true))
        // deleteFolder only removes its target and never renumbers survivors.
        assertEquals(listOf("b", "c"), ChatFolderPolicy.deleteFolder(abc, "a").map { it.id })
        assertEquals(abc, ChatFolderPolicy.deleteFolder(abc, "nope"))
    }
}
