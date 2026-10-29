package com.maodouchat.util

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G210b：`ChatFolderPolicy` —— 会话文件夹。KDoc 自己写着「纯函数，可单测」，零测试。
 *
 * 这里几条判据都是**会在将来被顺手改坏的**：
 * - 上限 28 / 名称 48 字（数字写错就是「用户建到第 29 个才报错」之类的怪事）；
 * - 文件夹名**大小写不敏感**去重（`Work` 与 `work` 算同一个——
 *   否则用户会建出一堆看起来一样的文件夹）；
 * - `moveFolder` 是**交换**语义、`reorderFolder` 是**插入**语义（注释里明确说了两者不同）；
 * - 会话**单归属**（`moveChatToFolder` 先从所有文件夹移除再放入目标），
 *   否则未读数会被重复计数。
 */
class ChatFolderPolicyTest {

    private fun folder(id: String, name: String, order: Int = 0, chats: List<String> = emptyList()) =
        ChatFolder(id = id, name = name, sortOrder = order, chatIds = chats)

    @Test
    fun limitsAreAsDocumented() {
        assertEquals(28, ChatFolderPolicy.MAX_FOLDERS, "文件夹上限应为 28")
        assertEquals(48, ChatFolderPolicy.MAX_NAME_LEN, "名称上限应为 48 字")
        assertTrue(ChatFolderPolicy.canCreateMore(27))
        assertFalse(ChatFolderPolicy.canCreateMore(28), "到 28 个就不能再建")
    }

    @Test
    fun normalizeNameTrimsCollapsesAndTruncates() {
        assertEquals("a b", ChatFolderPolicy.normalizeName("  a   b  "))
        assertEquals("", ChatFolderPolicy.normalizeName(null))
        assertEquals("", ChatFolderPolicy.normalizeName("   \t\n "))
        val long = ChatFolderPolicy.normalizeName("x".repeat(100))
        assertEquals(48, long.length, "应截到 48 字，实际 ${long.length}")
    }

    @Test
    fun createFolderEnforcesLimitsAndCaseInsensitiveUniqueness() {
        // 上限
        val full = (1..28).map { folder("f$it", "n$it") }
        assertNull(ChatFolderPolicy.createFolder(full, "one-more"), "满 28 个后必须拒绝")

        // 空名
        assertNull(ChatFolderPolicy.createFolder(emptyList(), "   "), "空名必须拒绝")

        // 大小写不敏感去重
        val base = listOf(folder("f1", "Work"))
        assertNull(ChatFolderPolicy.createFolder(base, "work"), "Work 与 work 应视为同名")
        assertNull(ChatFolderPolicy.createFolder(base, "  WORK  "), "归一化后同名也要拒绝")

        // 正常创建
        val created = ChatFolderPolicy.createFolder(base, "Family")
        assertNotNull(created)
        assertEquals(2, created.size)
        assertEquals("Family", created.last().name)
    }

    @Test
    fun renameFolderChecksUniquenessAgainstOtherFoldersOnly() {
        val folders = listOf(folder("f1", "Work"), folder("f2", "Family"))
        // f2 不能改名成 Work（撞别人）
        assertNull(ChatFolderPolicy.renameFolder(folders, "f2", "work"), "不能改成别人的名字")
        // 但 f2 保持自己的名字应被允许（不撞自己）
        val same = ChatFolderPolicy.renameFolder(folders, "f2", "family")
        assertNotNull(same)
        assertEquals("family", same.first { it.id == "f2" }.name)
        // 不存在的 id
        assertNull(ChatFolderPolicy.renameFolder(folders, "nope", "X"))
        // 空名
        assertNull(ChatFolderPolicy.renameFolder(folders, "f1", "  "))
    }

    @Test
    fun moveFolderSwapsSortOrderAndRejectsOutOfRange() {
        val folders = listOf(
            folder("a", "A", order = 0),
            folder("b", "B", order = 1),
            folder("c", "C", order = 2),
        )
        // 中间项上移：与 a 交换
        val moved = assertNotNull(ChatFolderPolicy.moveFolder(folders, "b", -1))
        assertEquals(listOf("b", "a", "c"), moved.map { it.id }, "上移应是交换语义")
        // 回到原位的往返
        val back = assertNotNull(ChatFolderPolicy.moveFolder(moved, "b", +1))
        assertEquals(listOf("a", "b", "c"), back.map { it.id })

        // 越界
        assertNull(ChatFolderPolicy.moveFolder(folders, "a", -1), "第一项不能再上移")
        assertNull(ChatFolderPolicy.moveFolder(folders, "c", +1), "最后一项不能再下移")
        assertNull(ChatFolderPolicy.moveFolder(folders, "nope", 1), "不存在的 id")
    }

    @Test
    fun reorderFolderUsesInsertionNotSwap() {
        // 这正是与 moveFolder 的区别：跨两位拖动时中间项依次前移
        val folders = listOf(
            folder("a", "A", order = 0),
            folder("b", "B", order = 1),
            folder("c", "C", order = 2),
            folder("d", "D", order = 3),
        )
        // 把 a（第 0 位）拖到第 2 位 → b,c 依次前移，a 落到 d 前面
        val reordered = assertNotNull(ChatFolderPolicy.reorderFolder(folders, "a", 2))
        assertEquals(listOf("b", "c", "a", "d"), reordered.map { it.id },
            "拖到第 2 位应是插入语义，不是和 c 交换")
        // 重排后 sortOrder 应连号（消除历史交换残留）
        assertEquals(listOf(0, 1, 2, 3), reordered.sortedBy { it.sortOrder }.map { it.sortOrder })

        // 目标越界被 coerce 进来
        val clamped = assertNotNull(ChatFolderPolicy.reorderFolder(folders, "a", 99))
        assertEquals(listOf("b", "c", "d", "a"), clamped.map { it.id }, "越界目标应收钳到末尾")
        // 原地不动
        assertEquals(listOf("a", "b", "c", "d"), assertNotNull(ChatFolderPolicy.reorderFolder(folders, "a", 0)).map { it.id })
        assertNull(ChatFolderPolicy.reorderFolder(folders, "nope", 1))
    }

    @Test
    fun chatHasExactlyOneFolder() {
        val folders = listOf(
            folder("f1", "F1", chats = listOf("c1", "c2")),
            folder("f2", "F2", chats = listOf("c3")),
        )
        // c1 从 f1 移到 f2：先全移除，再放进目标（单归属）
        val moved = ChatFolderPolicy.moveChatToFolder(folders, "c1", "f2")
        assertFalse("c1" in moved.first { it.id == "f1" }.chatIds, "c1 必须从原文件夹移除")
        assertEquals(listOf("c3", "c1"), moved.first { it.id == "f2" }.chatIds)

        // 从所有文件夹移除（target=null）
        val removed = ChatFolderPolicy.moveChatToFolder(folders, "c1", null)
        assertTrue(removed.none { "c1" in it.chatIds }, "target=null 应从所有文件夹移除")

        // 空白 chatId 不动
        assertEquals(folders, ChatFolderPolicy.moveChatToFolder(folders, "  ", "f2"))

        // 单归属推论：folderOfChat 至多返回一个
        assertEquals(1, moved.count { "c1" in it.chatIds }, "一个会话只能属于一个文件夹")
    }

    @Test
    fun setChatInFolderAddsIdempotentlyAndRemoves() {
        val one = listOf(folder("f1", "F1", chats = listOf("c1")))
        assertEquals(
            listOf("c1"), ChatFolderPolicy.setChatInFolder(one, "f1", "c1", included = true).first().chatIds,
            "重复加入不应产生重复项",
        )
        assertEquals(
            listOf("c1", "c2"), ChatFolderPolicy.setChatInFolder(one, "f1", "c2", included = true).first().chatIds,
            "加入新会话应追加到末尾",
        )
        assertEquals(
            emptyList(), ChatFolderPolicy.setChatInFolder(one, "f1", "c1", included = false).first().chatIds,
        )
        // 空白 chatId / 不存在的 folder 都不该改
        assertEquals(one, ChatFolderPolicy.setChatInFolder(one, "f1", " ", true))
        assertEquals(one, ChatFolderPolicy.setChatInFolder(one, "nope", "c9", true))
    }

    @Test
    fun filterAndUnreadCountingBehave() {
        val f = folder("f1", "F1", chats = listOf("c1", "c3"))
        assertEquals(listOf("c1", "c3"), ChatFolderPolicy.filterChatIds(f, listOf("c1", "c2", "c3", "c4")))
        // folder 为 null = 全部
        assertEquals(listOf("c1", "c2"), ChatFolderPolicy.filterChatIds(null, listOf("c1", "c2")))

        // 未读数求和：缺失按 0，负数按 0（不产生负数徽标）
        val folderWithChats = folder("f1", "F1", chats = listOf("c1", "c2", "c3"))
        val unread = mapOf("c1" to 3, "c2" to 0)
        assertEquals(3, ChatFolderPolicy.unreadInFolder(folderWithChats, unread))
        assertEquals(
            0, ChatFolderPolicy.unreadInFolder(folderWithChats, mapOf("c1" to -5)),
            "负未读数应按 0 计",
        )
    }

    @Test
    fun systemFiltersAreRecognized() {
        listOf(
            ChatFolderPolicy.SYSTEM_GROUPS_ID,
            ChatFolderPolicy.SYSTEM_DIRECT_ID,
            ChatFolderPolicy.SYSTEM_UNREAD_ID,
            ChatFolderPolicy.SYSTEM_SECRET_ID,
            ChatFolderPolicy.SYSTEM_LOCKED_ID,
        ).forEach { assertTrue(ChatFolderPolicy.isSystemFilter(it), "$it 应被识别为内置筛选项") }

        // 用户文件夹 id 与空值都不是内置筛选项
        assertFalse(ChatFolderPolicy.isSystemFilter("f1"))
        assertFalse(ChatFolderPolicy.isSystemFilter(null))
        assertFalse(ChatFolderPolicy.isSystemFilter(ChatFolderPolicy.ALL_ID),
            "ALL_ID 是「全部」而不是内置筛选项")
    }

    @Test
    fun unreadChatIncludesMarkedUnread() {
        assertTrue(ChatFolderPolicy.isUnreadChat(unreadCount = 1, markedUnread = false))
        assertTrue(ChatFolderPolicy.isUnreadChat(unreadCount = 0, markedUnread = true),
            "手动标记未读也要算未读")
        assertFalse(ChatFolderPolicy.isUnreadChat(unreadCount = 0, markedUnread = false))
        assertFalse(ChatFolderPolicy.isUnreadChat(unreadCount = -3, markedUnread = false),
            "负数未读不算未读")
    }

    @Test
    fun deleteFolderOnlyRemovesThatOne() {
        val folders = listOf(folder("a", "A"), folder("b", "B"), folder("c", "C"))
        assertEquals(listOf("b", "c"), ChatFolderPolicy.deleteFolder(folders, "a").map { it.id })
        assertEquals(folders, ChatFolderPolicy.deleteFolder(folders, "nope"), "删不存在的应原样返回")
    }

    @Test
    fun createFolderAssignsNextOrderAndSortsResult() {
        val existing = listOf(folder("b", "B", order = 5), folder("a", "A", order = 2))
        val created = assertNotNull(ChatFolderPolicy.createFolder(existing, "New", id = "n1"))
        assertEquals("n1", created.last().id)
        assertEquals(6, created.last().sortOrder)
        assertEquals(listOf("a", "b", "n1"), created.map { it.id })
    }

    @Test
    fun moveFolderDownSwapsAndZeroDeltaIsIdentity() {
        val folders = listOf(
            folder("a", "A", order = 0),
            folder("b", "B", order = 1),
            folder("c", "C", order = 2),
        )
        val moved = assertNotNull(ChatFolderPolicy.moveFolder(folders, "a", +1))
        assertEquals(listOf("b", "a", "c"), moved.map { it.id })
        val same = assertNotNull(ChatFolderPolicy.moveFolder(folders, "b", 0))
        assertEquals(listOf("a", "b", "c"), same.map { it.id })
    }

    @Test
    fun reorderFolderBackwardMoveShiftsMiddleForward() {
        val folders = listOf(
            folder("a", "A", order = 0),
            folder("b", "B", order = 1),
            folder("c", "C", order = 2),
            folder("d", "D", order = 3),
        )
        val reordered = assertNotNull(ChatFolderPolicy.reorderFolder(folders, "d", 1))
        assertEquals(listOf("a", "d", "b", "c"), reordered.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), reordered.map { it.sortOrder })
        val clamped = assertNotNull(ChatFolderPolicy.reorderFolder(folders, "c", -5))
        assertEquals(listOf("c", "a", "b", "d"), clamped.map { it.id })
    }

    @Test
    fun folderOfChatReturnsFirstAndFilterKeepsInputOrder() {
        val folders = listOf(
            folder("f1", "F1", chats = listOf("c3", "c1")),
            folder("f2", "F2", chats = listOf("c1")),
        )
        assertEquals("f1", ChatFolderPolicy.folderOfChat(folders, "c1")?.id)
        assertNull(ChatFolderPolicy.folderOfChat(folders, "c9"))
        val f = folder("f1", "F1", chats = listOf("c3", "c1"))
        assertEquals(listOf("c1", "c3"), ChatFolderPolicy.filterChatIds(f, listOf("c1", "c2", "c3")))
    }

    @Test
    fun createFolderNormalizesBeforeComparing() {
        val base = listOf(folder("f1", "a b"))
        assertNull(ChatFolderPolicy.createFolder(base, "a   b"))
        val long = listOf(folder("f1", "x".repeat(48)))
        assertNull(ChatFolderPolicy.createFolder(long, "x".repeat(100)))
        val created = assertNotNull(ChatFolderPolicy.createFolder(emptyList(), "x".repeat(100)))
        assertEquals(48, created.last().name.length)
    }

    @Test
    fun createFolderOnEmptyListStartsAtZeroWithGeneratedId() {
        val created = assertNotNull(ChatFolderPolicy.createFolder(emptyList(), "Work"))
        assertEquals(1, created.size)
        assertEquals(0, created.first().sortOrder)
        assertTrue(created.first().id.startsWith("folder_"))
    }

    @Test
    fun renameFolderNormalizesAndPreservesOrderAndChats() {
        val folders = listOf(folder("f1", "Work", order = 3, chats = listOf("c1", "c2")))
        val renamed = assertNotNull(ChatFolderPolicy.renameFolder(folders, "f1", "  New   Name  "))
        assertEquals("New Name", renamed.first().name)
        assertEquals(3, renamed.first().sortOrder)
        assertEquals(listOf("c1", "c2"), renamed.first().chatIds)
    }

    @Test
    fun moveFolderTwoStepsSwapsWithTheDistantFolder() {
        val folders = listOf(
            folder("a", "A", order = 0),
            folder("b", "B", order = 1),
            folder("c", "C", order = 2),
        )
        val moved = assertNotNull(ChatFolderPolicy.moveFolder(folders, "a", +2))
        assertEquals(listOf("c", "b", "a"), moved.map { it.id })
        assertNull(ChatFolderPolicy.moveFolder(folders, "a", +3))
    }

    @Test
    fun moveChatToUnknownTargetRemovesItEverywhere() {
        val folders = listOf(
            folder("f1", "F1", chats = listOf("c1", "c2")),
            folder("f2", "F2", chats = listOf("c3")),
        )
        val moved = ChatFolderPolicy.moveChatToFolder(folders, "c1", "nope")
        assertTrue(moved.none { "c1" in it.chatIds })
        assertEquals(listOf("c2"), moved.first { it.id == "f1" }.chatIds)
        assertEquals(listOf("c3"), moved.first { it.id == "f2" }.chatIds)
    }

    @Test
    fun setChatRemoveAbsentLeavesFoldersUntouched() {
        val folders = listOf(
            folder("f1", "F1", chats = listOf("c1")),
            folder("f2", "F2", chats = listOf("c2")),
        )
        val result = ChatFolderPolicy.setChatInFolder(folders, "f1", "c9", included = false)
        assertEquals(folders, result)
    }

    @Test
    fun reorderFolderSameIndexKeepsOriginalSortOrders() {
        // 移到原位只排序不重排号：历史间隔/碰撞原样保留
        val folders = listOf(folder("b", "B", 5), folder("a", "A", 3))
        val reordered = ChatFolderPolicy.reorderFolder(folders, "a", 0)
        assertNotNull(reordered)
        assertEquals(listOf("a", "b"), reordered.map { it.id })
        assertEquals(listOf(3, 5), reordered.map { it.sortOrder })
    }

    @Test
    fun reorderFolderClampsOutOfRangeTargets() {
        val folders = listOf(folder("a", "A", 0), folder("b", "B", 1), folder("c", "C", 2))
        val toEnd = ChatFolderPolicy.reorderFolder(folders, "a", 99)
        assertNotNull(toEnd)
        assertEquals(listOf("b", "c", "a"), toEnd.map { it.id })
        assertEquals(listOf(0, 1, 2), toEnd.map { it.sortOrder })
        val toStart = ChatFolderPolicy.reorderFolder(folders, "c", -5)
        assertNotNull(toStart)
        assertEquals(listOf("c", "a", "b"), toStart.map { it.id })
        assertEquals(listOf(0, 1, 2), toStart.map { it.sortOrder })
    }

    @Test
    fun unknownIdsReturnNullForReorderAndRename() {
        val folders = listOf(folder("f1", "Work"), folder("f2", "Family"))
        assertNull(ChatFolderPolicy.reorderFolder(folders, "missing", 0))
        assertNull(ChatFolderPolicy.renameFolder(folders, "missing", "Brand"))
    }

    @Test
    fun moveChatToNullTargetPurgesEverywhereAndBlankChatIsNoop() {
        val folders = listOf(
            folder("f1", "Work", chats = listOf("c1", "c2")),
            folder("f2", "Family", chats = listOf("c1")),
        )
        val purged = ChatFolderPolicy.moveChatToFolder(folders, "c1", null)
        assertTrue(purged.none { "c1" in it.chatIds })
        assertEquals(listOf("c2"), purged.first { it.id == "f1" }.chatIds)
        assertEquals(folders, ChatFolderPolicy.moveChatToFolder(folders, "  ", "f1"))
    }

    @Test
    fun unreadAndLookupEdgeCases() {
        val folderWithChats = folder("f1", "Work", chats = listOf("x", "y", "z"))
        assertEquals(3, ChatFolderPolicy.unreadInFolder(folderWithChats, mapOf("x" to -5, "y" to 3)))
        assertFalse(ChatFolderPolicy.isUnreadChat(-3, false))
        assertTrue(ChatFolderPolicy.isUnreadChat(-3, true))
        assertFalse(ChatFolderPolicy.isSystemFilter(null))
        assertFalse(ChatFolderPolicy.isSystemFilter("system:unknown"))
        assertFalse(ChatFolderPolicy.isSystemFilter(""))
        assertNull(ChatFolderPolicy.folderOfChat(listOf(folderWithChats), "missing"))
        assertEquals(
            listOf("c3", "c1"),
            ChatFolderPolicy.filterChatIds(null, listOf("c3", "c1")),
        )
    }
}
