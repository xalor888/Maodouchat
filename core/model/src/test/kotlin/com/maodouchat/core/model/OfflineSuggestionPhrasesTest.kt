package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSuggestionPhrasesTest {

    @Test
    fun greetingMatchReturnsGreetingReplies() {
        val replies = OfflineSuggestionPhrases.suggest("谢谢", "谢谢", "friendly")
        assertEquals(listOf("不客气～", "应该的，有需要再叫我。", "小事一桩。"), replies)
    }

    @Test
    fun unknownInputFallsBackToGenericReplies() {
        val replies = OfflineSuggestionPhrases.suggest("xyz", "xyz", "friendly")
        assertEquals(listOf("收到，我晚点仔细回你。", "明白了。", "嗯嗯，继续说。"), replies)
    }

    @Test
    fun formalToneReplacesTilde() {
        // “在吗”含“吗”会先命中疑问句优先分支，走不到问候分支；用“你好”命中问候分支。
        val replies = OfflineSuggestionPhrases.suggest("你好", "你好", "formal")
        assertTrue(replies.contains("嗨，刚看到。"))
        assertTrue(replies.none { it.contains("～") })
    }

    @Test
    fun conciseToneTruncatesToFourteenChars() {
        val replies = OfflineSuggestionPhrases.suggest("谢谢", "谢谢", "concise")
        assertTrue(replies.all { it.length <= 14 })
    }

    @Test
    fun humorousToneAppendsSmiley() {
        val replies = OfflineSuggestionPhrases.suggest("谢谢", "谢谢", "humorous")
        assertTrue(replies.all { it.endsWith(" :)") })
    }

    @Test
    fun suggestNeverReturnsMoreThanFour() {
        val replies = OfflineSuggestionPhrases.suggest("谢谢", "谢谢", "friendly")
        assertTrue(replies.size <= 4)
    }
}
