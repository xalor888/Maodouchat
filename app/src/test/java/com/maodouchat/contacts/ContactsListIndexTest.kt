package com.maodouchat.contacts

import com.maodouchat.data.model.User
import com.maodouchat.ui.screen.contacts.findLetterIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsListIndexTest {
    @Test
    fun `letter index includes every flattened contact row`() {
        val grouped = linkedMapOf(
            "A" to listOf(user("a1"), user("a2")),
            "B" to listOf(user("b1")),
            "Z" to listOf(user("z1"), user("z2"), user("z3"))
        )

        // 无好友请求/群邀请时，前置固定项为「添加联系人」+「新群聊」+「新频道」(leadingFixedItems=3)
        assertEquals(3, findLetterIndex(grouped, 0, 0, 0, "A"))
        assertEquals(6, findLetterIndex(grouped, 0, 0, 0, "B"))
        assertEquals(8, findLetterIndex(grouped, 0, 0, 0, "Z"))
        assertEquals(-1, findLetterIndex(grouped, 0, 0, 0, "Q"))
        // 2 条收到请求、1 条发出请求：3 + (2+1) + (1+1) = 8
        assertEquals(8, findLetterIndex(grouped, 2, 1, 0, "A"))
        assertEquals(11, findLetterIndex(grouped, 2, 1, 0, "B"))
        // 再加 1 条群邀请（header+1）：8 + 2 = 10
        assertEquals(10, findLetterIndex(grouped, 2, 1, 1, "A"))
        assertEquals(13, findLetterIndex(grouped, 2, 1, 1, "B"))
    }

    private fun user(id: String) = User(id = id, name = id)

    @Test
    fun `letter index on empty grouped map is always minus one`() {
        assertEquals(-1, findLetterIndex(emptyMap(), 0, 0, 0, "A"))
        assertEquals(-1, findLetterIndex(emptyMap(), 5, 3, 2, "A"))
    }

    @Test
    fun `letter index counts empty sections as header only`() {
        val grouped = linkedMapOf(
            "A" to emptyList(),
            "B" to listOf(user("b1"))
        )
        // A 节无联系人：B 的索引 = 3（固定项）+ 1（A 的 header）
        assertEquals(3, findLetterIndex(grouped, 0, 0, 0, "A"))
        assertEquals(4, findLetterIndex(grouped, 0, 0, 0, "B"))
    }

    @Test
    fun `letter index ignores negative request counts`() {
        val grouped = linkedMapOf(
            "A" to listOf(user("a1"), user("a2")),
            "B" to listOf(user("b1"))
        )
        // 负数请求数不产生 header+行，前导仍是 3
        assertEquals(3, findLetterIndex(grouped, -2, -1, -5, "A"))
        assertEquals(6, findLetterIndex(grouped, -2, -1, -5, "B"))
    }

    @Test
    fun `letter index shifts by group invites`() {
        val grouped = linkedMapOf(
            "A" to listOf(user("a1"), user("a2")),
            "B" to listOf(user("b1")),
            "Z" to listOf(user("z1"), user("z2"), user("z3"))
        )
        // 2 条群邀请 → 前导 3 + (2+1)
        assertEquals(6, findLetterIndex(grouped, 0, 0, 2, "A"))
        assertEquals(9, findLetterIndex(grouped, 0, 0, 2, "B"))
        assertEquals(11, findLetterIndex(grouped, 0, 0, 2, "Z"))
    }

    @Test
    fun `letter index is case sensitive and deterministic`() {
        val grouped = linkedMapOf(
            "A" to listOf(user("a1"), user("a2")),
            "B" to listOf(user("b1"))
        )
        // 小写 a 不是分组键
        assertEquals(-1, findLetterIndex(grouped, 0, 0, 0, "a"))
        // 重复调用结果稳定
        assertEquals(
            findLetterIndex(grouped, 2, 1, 1, "B"),
            findLetterIndex(grouped, 2, 1, 1, "B")
        )
    }
}
