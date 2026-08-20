package com.maodouchat.data

import com.maodouchat.data.repository.MessageSearchTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索分词器契约测试（9.235）。分词是索引/查询两端共用的对齐契约：
 * 任何一端变化都会造成「搜不到已有消息」的静默回归，故锁定全部规则。
 */
class MessageSearchTokenizerTest {

    @Test
    fun `latin word emits itself and prefixes 3 to 8`() {
        val tokens = MessageSearchTokenizer.tokens("hello")
        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("hel"))
        assertTrue(tokens.contains("hell"))
        // 长度 3..min(8, len-1)：hello 长 5 → 前缀 hel/hell（hello 本体已含）
        assertFalse(tokens.contains("he"))
    }

    @Test
    fun `short latin words under 4 chars emit only whole word`() {
        val tokens = MessageSearchTokenizer.tokens("ok go")
        assertTrue(tokens.contains("ok"))
        assertTrue(tokens.contains("go"))
        assertEquals(2, tokens.size)
    }

    @Test
    fun `han run emits singles bigrams and trigrams`() {
        val tokens = MessageSearchTokenizer.tokens("今天天气")
        // 单字
        listOf("今", "天", "气").forEach { assertTrue(tokens.contains(it)) }
        // 二元
        assertTrue(tokens.contains("今天"))
        assertTrue(tokens.contains("天天"))
        assertTrue(tokens.contains("天气"))
        // 三元
        assertTrue(tokens.contains("今天天"))
        assertTrue(tokens.contains("天天气"))
    }

    @Test
    fun `mixed han and latin split at boundary`() {
        val tokens = MessageSearchTokenizer.tokens("开会android版本")
        assertTrue(tokens.contains("开会"))
        assertTrue(tokens.contains("android"))
        assertTrue(tokens.contains("版本"))
        // 边界处不产生混合 token
        assertFalse(tokens.contains("会android"))
    }

    @Test
    fun `nfkc normalization folds fullwidth and case`() {
        val tokens = MessageSearchTokenizer.tokens("Ｈｅｌｌｏ ＷＯＲＬＤ")
        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("world"))
    }

    @Test
    fun `punctuation separates words`() {
        val tokens = MessageSearchTokenizer.tokens("foo,bar;baz")
        assertTrue(tokens.containsAll(listOf("foo", "bar", "baz")))
        assertFalse(tokens.contains("foobar"))
    }

    @Test
    fun `blank and symbol only input yields empty`() {
        assertTrue(MessageSearchTokenizer.tokens("").isEmpty())
        assertTrue(MessageSearchTokenizer.tokens("   ").isEmpty())
        assertTrue(MessageSearchTokenizer.tokens("!!!???").isEmpty())
    }

    @Test
    fun `token cap is 200`() {
        // 大量不同汉字：单字+二元+三元很快超 200，必须截断
        val text = ('一'..'龥').take(300).joinToString("")
        val tokens = MessageSearchTokenizer.tokens(text)
        assertEquals(200, tokens.size)
    }

    @Test
    fun `very long latin word truncated to 40`() {
        val longWord = "a".repeat(100)
        val tokens = MessageSearchTokenizer.tokens(longWord)
        assertTrue(tokens.contains("a".repeat(40)))
        assertFalse(tokens.any { it.length > 40 })
    }

    @Test
    fun `dedup keeps first occurrence order`() {
        val tokens = MessageSearchTokenizer.tokens("cat cat cat")
        assertEquals(1, tokens.count { it == "cat" })
    }

    @Test
    fun `index and query sides align for common searches`() {
        // 契约核心：文档分词与查询分词必须对称，否则搜不到
        val doc = MessageSearchTokenizer.tokens("下周的项目会议改到周三下午")
        val query = MessageSearchTokenizer.tokens("项目会议")
        assertTrue(query.any { it in doc })
        val docEn = MessageSearchTokenizer.tokens("Deploy the release build")
        val queryEn = MessageSearchTokenizer.tokens("rele")
        assertTrue(queryEn.any { it in docEn })
    }
}
