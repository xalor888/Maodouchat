package com.maodouchat.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsIndexPolicyTest {
    @Test
    fun `initial prefers latin uppercase then cjk then hash`() {
        assertEquals("A", ContactsIndexPolicy.initialFor("alice"))
        assertEquals("Z", ContactsIndexPolicy.initialFor("Zoe"))
        assertEquals("张", ContactsIndexPolicy.initialFor("张三"))
        assertEquals("#", ContactsIndexPolicy.initialFor("123"))
        assertEquals("#", ContactsIndexPolicy.initialFor("  "))
        assertEquals("#", ContactsIndexPolicy.initialFor(""))
    }

    @Test
    fun `letter list index skips leading fixed rows and section headers`() {
        val letters = listOf("A", "B", "张")
        val sizes = mapOf("A" to 2, "B" to 1, "张" to 3)
        assertEquals(1, ContactsIndexPolicy.letterListIndex(letters, sizes, "A"))
        assertEquals(4, ContactsIndexPolicy.letterListIndex(letters, sizes, "B"))
        assertEquals(6, ContactsIndexPolicy.letterListIndex(letters, sizes, "张"))
        assertEquals(-1, ContactsIndexPolicy.letterListIndex(letters, sizes, "Q"))
    }

    @Test
    fun `leadingFixedItemCount includes requests invites and both action rows`() {
        // 默认始终有「添加联系人」+「新群聊」+「新频道」三行
        assertEquals(3, ContactsIndexPolicy.leadingFixedItemCount(0, 0, 0))
        // 2 incoming → header+2；1 outgoing → header+1；1 invite → header+1；+3 action rows
        assertEquals(3 + 3 + 2 + 2, ContactsIndexPolicy.leadingFixedItemCount(2, 1, 1))
        assertEquals(0, ContactsIndexPolicy.leadingFixedItemCount(0, 0, 0, trailingActionRows = 0))
    }

    @Test
    fun `groupByInitial sorts keys`() {
        val grouped = ContactsIndexPolicy.groupByInitial(
            listOf("u1" to "bob", "u2" to "alice", "u3" to "张三", "u4" to "9bot")
        )
        assertEquals(listOf("#", "A", "B", "张"), grouped.keys.toList())
        assertEquals(listOf("u2"), grouped["A"])
        assertEquals(listOf("u4"), grouped["#"])
    }

    @Test
    fun `initialFor trims surrounding whitespace before taking first char`() {
        // 前导空白（空格/制表符/换行）不影响首字母判定
        assertEquals("A", ContactsIndexPolicy.initialFor("  alice"))
        assertEquals("B", ContactsIndexPolicy.initialFor("\tbob\n"))
        // 全空白已被 isBlank 拦下
        assertEquals("#", ContactsIndexPolicy.initialFor(" \t\n "))
    }

    @Test
    fun `initialFor hashes non latin non cjk initials`() {
        // 非 ASCII 拉丁变音字母不归入 A-Z
        assertEquals("#", ContactsIndexPolicy.initialFor("Émilie"))
        // 全角数字不是 ASCII 数字
        assertEquals("#", ContactsIndexPolicy.initialFor("１２３"))
        // CJK 范围边界内仍是汉字首字
        assertEquals("中", ContactsIndexPolicy.initialFor("中文"))
    }

    @Test
    fun `leadingFixedItemCount ignores non positive request counts`() {
        // 负数请求数不产生 header+行（判定是 > 0）
        assertEquals(3, ContactsIndexPolicy.leadingFixedItemCount(-5, -1, -2))
        // 负数尾行数钳制为 0
        assertEquals(0, ContactsIndexPolicy.leadingFixedItemCount(0, 0, 0, trailingActionRows = -2))
        // 只有 incoming 为正时只加它
        assertEquals(3 + 4, ContactsIndexPolicy.leadingFixedItemCount(3, 0, -1))
    }

    @Test
    fun `letterListIndex treats missing sizes as zero`() {
        val letters = listOf("A", "B")
        val sizes = mapOf("A" to 2)
        // 缺失的 B 按 0 行算：A 占 header+2，B 紧随其后
        assertEquals(1, ContactsIndexPolicy.letterListIndex(letters, sizes, "A"))
        assertEquals(4, ContactsIndexPolicy.letterListIndex(letters, sizes, "B"))
        // 空字母表直接 -1
        assertEquals(-1, ContactsIndexPolicy.letterListIndex(emptyList(), emptyMap(), "A"))
    }

    @Test
    fun `groupByInitial keeps ids order and handles empty input`() {
        assertEquals(emptyMap<String, List<String>>(), ContactsIndexPolicy.groupByInitial(emptyList()))
        val grouped = ContactsIndexPolicy.groupByInitial(
            listOf("u1" to "amy", "u2" to "andy", "u3" to "amy")
        )
        // 同字母内保持原输入顺序，名字相同的两个 id 都在
        assertEquals(listOf("u1", "u2", "u3"), grouped["A"])
    }
}
