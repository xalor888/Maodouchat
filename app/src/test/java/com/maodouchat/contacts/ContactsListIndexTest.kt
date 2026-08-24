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
}
