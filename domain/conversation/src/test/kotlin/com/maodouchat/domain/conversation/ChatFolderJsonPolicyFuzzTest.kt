package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ChatFolderJsonPolicy 的 fuzz：会话文件夹 JSON 编解码的往返恒等与边界钉住。
// 只加测试、零生产改动；断言依据是编解码的归一化契约（name 经
// ChatFolderPolicy.normalizeName、chatIds 去空去重保序、sortOrder 缺失时取下标、
// 解码结果按 sortOrder 排序、损坏 JSON 回空列表）。
class ChatFolderJsonPolicyFuzzTest {

    private companion object {
        private const val ITERATIONS = 150
    }

    private fun randomToken(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    // 已归一化的名字：无首尾空白、无连续空白、长度 ≤48，normalizeName 下恒等。
    private fun randomName(random: Random): String {
        val words = (1..random.nextInt(1, 4)).map { randomToken(random, random.nextInt(1, 10)) }
        return words.joinToString(" ").take(48).trimEnd()
    }

    private fun randomFolder(random: Random, index: Int): ChatFolder {
        val chatCount = random.nextInt(0, 6)
        val chatIds = (0 until chatCount).map { j -> "c$index-$j-" + randomToken(random, random.nextInt(1, 8)) }
        return ChatFolder(
            id = "f$index-" + randomToken(random, random.nextInt(1, 8)),
            name = randomName(random),
            chatIds = chatIds,
            sortOrder = random.nextInt(-10, 100)
        )
    }

    @Test
    fun `folder list round-trips on seeded random folders`() {
        val random = Random(31006)
        repeat(ITERATIONS) { i ->
            val count = random.nextInt(0, 6)
            val folders = (0 until count).map { randomFolder(random, i * 10 + it) }
            val decoded = ChatFolderJsonPolicy.decodeFolders(ChatFolderJsonPolicy.encodeFolders(folders))
            assertEquals(folders.sortedBy { it.sortOrder }, decoded, "iteration $i: folders must round-trip sorted by sortOrder")
        }
    }

    @Test
    fun `decode never throws on seeded random garbage`() {
        val random = Random(31007)
        repeat(200) { i ->
            val raw = when (random.nextInt(4)) {
                0 -> randomToken(random, random.nextInt(0, 60))
                1 -> "[" + randomToken(random, random.nextInt(0, 40)) + "]"
                2 -> "x".repeat(random.nextInt(0, 120))
                else -> """[{"id":"f","name":"n"}]"""
            }
            val decoded = ChatFolderJsonPolicy.decodeFolders(raw)
            assertTrue(
                decoded.zipWithNext().all { (a, b) -> a.sortOrder <= b.sortOrder },
                "iteration $i: decoded folders must be sorted by sortOrder"
            )
            assertTrue(
                decoded.all { it.id.isNotBlank() && it.name.isNotBlank() },
                "iteration $i: decoded folders must have non-blank id and name"
            )
        }
        assertEquals(emptyList(), ChatFolderJsonPolicy.decodeFolders("not json"))
        assertEquals(emptyList(), ChatFolderJsonPolicy.decodeFolders(""))
        assertEquals(emptyList(), ChatFolderJsonPolicy.decodeFolders("null"))
        assertEquals(emptyList(), ChatFolderJsonPolicy.decodeFolders("[1,2]"))
    }

    @Test
    fun `duplicate and blank chatIds are cleaned`() {
        val folder = ChatFolder(
            id = "f1", name = "工作", chatIds = listOf("c1", "c2", "c1", "c3", "c2"), sortOrder = 0
        )
        val decoded = ChatFolderJsonPolicy.decodeFolders(ChatFolderJsonPolicy.encodeFolders(listOf(folder)))
        assertEquals(listOf("c1", "c2", "c3"), decoded.single().chatIds)
        val raw = """[{"id":"f1","name":"n","sortOrder":0,"chatIds":["c1","","  ","c2"]}]"""
        assertEquals(listOf("c1", "c2"), ChatFolderJsonPolicy.decodeFolders(raw).single().chatIds)
    }

    @Test
    fun `blank id or blank name entries are skipped`() {
        val raw = "[" +
            """{"id":"","name":"x","sortOrder":0,"chatIds":[]},""" +
            """{"id":"f1","name":"   ","sortOrder":0,"chatIds":[]},""" +
            """{"id":"ok","name":"n","sortOrder":0,"chatIds":[]}]"""
        val decoded = ChatFolderJsonPolicy.decodeFolders(raw)
        assertEquals(listOf("ok"), decoded.map { it.id })
    }

    @Test
    fun `missing sortOrder defaults to array index`() {
        val raw = "[" +
            """{"id":"a","name":"A","chatIds":[]},""" +
            """{"id":"b","name":"B","chatIds":[]}]"""
        val decoded = ChatFolderJsonPolicy.decodeFolders(raw)
        assertEquals(listOf(0, 1), decoded.map { it.sortOrder })
    }

    @Test
    fun `name normalization is pinned`() {
        val raw = """[{"id":"f1","name":"  工作   学习  ","sortOrder":0,"chatIds":[]}]"""
        assertEquals("工作 学习", ChatFolderJsonPolicy.decodeFolders(raw).single().name)
        val longName = "x".repeat(50)
        val rawLong = """[{"id":"f1","name":"$longName","sortOrder":0,"chatIds":[]}]"""
        assertEquals("x".repeat(48), ChatFolderJsonPolicy.decodeFolders(rawLong).single().name)
    }
}
