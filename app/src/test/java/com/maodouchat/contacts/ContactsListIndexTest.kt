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

        // 无好友请求时，前置固定项仅「新群聊」一行(leadingFixedItems=1)
        assertEquals(1, findLetterIndex(grouped, 0, 0, "A"))
        assertEquals(4, findLetterIndex(grouped, 0, 0, "B"))
        assertEquals(6, findLetterIndex(grouped, 0, 0, "Z"))
        assertEquals(-1, findLetterIndex(grouped, 0, 0, "Q"))
        // 有 2 条收到请求、1 条发出请求时，前置项 = 1 + (2+1) + (1+1) = 6
        assertEquals(6, findLetterIndex(grouped, 2, 1, "A"))
        assertEquals(9, findLetterIndex(grouped, 2, 1, "B"))
    }

    private fun user(id: String) = User(id = id, name = id)
}
