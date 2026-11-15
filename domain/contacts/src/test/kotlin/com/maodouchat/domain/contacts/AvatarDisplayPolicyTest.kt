package com.maodouchat.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarDisplayPolicyTest {

    @Test
    fun `empty name falls back to a question mark`() {
        assertEquals("?", AvatarDisplayPolicy.initial(""))
    }

    @Test
    fun `initial is the first character`() {
        assertEquals("A", AvatarDisplayPolicy.initial("Alice"))
        assertEquals("张", AvatarDisplayPolicy.initial("张三"))
        assertEquals(" ", AvatarDisplayPolicy.initial(" Bob"))
    }

    @Test
    fun `gradient index always lands inside the gradient list`() {
        val names = listOf(
            "", "Alice", "Bob", "Charlie", "张三", "李四",
            "a", "zzzzzzzzzz", "user_12345", "😀emoji",
        )
        names.forEach { name ->
            val index = AvatarDisplayPolicy.gradientIndex(name, 6)
            assertTrue("$name 的序号应在 [0, 6)：$index", index in 0 until 6)
        }
    }

    @Test
    fun `gradient index is stable for the same name`() {
        listOf("Alice", "Bob", "张三", "").forEach { name ->
            assertEquals(
                AvatarDisplayPolicy.gradientIndex(name, 6),
                AvatarDisplayPolicy.gradientIndex(name, 6),
            )
        }
    }

    @Test
    fun `a single gradient always yields index zero`() {
        listOf("Alice", "Bob", "").forEach { name ->
            assertEquals(0, AvatarDisplayPolicy.gradientIndex(name, 1))
        }
    }

    @Test
    fun `many names never escape the gradient range`() {
        (0 until 500).forEach { i ->
            val index = AvatarDisplayPolicy.gradientIndex("user$i", 6)
            assertTrue("user$i 的序号越界：$index", index in 0 until 6)
        }
    }
}
