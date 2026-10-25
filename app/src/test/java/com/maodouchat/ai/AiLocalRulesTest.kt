package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiLocalRulesTest {

    @Test
    fun `classify text picks dominant lexicon`() {
        assertEquals(
            AiMessageClassifierCategory.FINANCE,
            AiMessageClassifier.classifyText("记得转账，账单发我，付款截图也发一下").category
        )
        assertEquals(
            AiMessageClassifierCategory.TECH,
            AiMessageClassifier.classifyText("服务器部署完了，接口 bug 修复后发版本").category
        )
    }

    @Test
    fun `classify text without hits falls back to other`() {
        val result = AiMessageClassifier.classifyText("随便聊聊天气")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertTrue(result.confidence < 0.5)
    }

    @Test
    fun `classify confidence is share of hits`() {
        // 单词典全命中时置信度为 1.0
        val result = AiMessageClassifier.classifyText("deadline 截止 提交 待办")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertTrue(result.confidence > 0.6)
    }

    @Test
    fun `classify truncates long input to scan window`() {
        // 关键词在 300 字符窗口外不应命中
        val text = "垫".repeat(400) + "转账 账单"
        val result = AiMessageClassifier.classifyText(text)
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
    }

    @Test
    fun `detect emotion picks strongest signal`() {
        val result = AiEmotionReply.detectEmotion(listOf("今天好开心哈哈", "太棒了"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertTrue(result.confidence > 0.5)
    }

    @Test
    fun `detect emotion neutral when no hits`() {
        val result = AiEmotionReply.detectEmotion(listOf("会议改到三点"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion empty input is neutral`() {
        assertEquals(Emotion.NEUTRAL, AiEmotionReply.detectEmotion(emptyList()).emotion)
    }

    @Test
    fun `detect emotion mixed signals take majority`() {
        val result = AiEmotionReply.detectEmotion(listOf("难过 伤心", "难过"))
        assertEquals(Emotion.SAD, result.emotion)
    }

    @Test
    fun `classify tie breaks to first max category`() {
        val result = AiMessageClassifier.classifyText("转账 部署")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify english lexicon is case insensitive`() {
        val result = AiMessageClassifier.classifyText("Please DEPLOY the new API version")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion ignores hits beyond scan window`() {
        val far = AiEmotionReply.detectEmotion(listOf("垫".repeat(250) + "开心"))
        assertEquals(Emotion.NEUTRAL, far.emotion)
        val near = AiEmotionReply.detectEmotion(listOf("垫".repeat(190) + "开心"))
        assertEquals(Emotion.HAPPY, near.emotion)
    }

    @Test
    fun `detect emotion tie across texts picks first max`() {
        val result = AiEmotionReply.detectEmotion(listOf("哈哈", "难过"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify blank text falls back to other`() {
        val result = AiMessageClassifier.classifyText("   ")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `classify notice study and social lexicons`() {
        assertEquals(
            AiMessageClassifierCategory.NOTICE,
            AiMessageClassifier.classifyText("系统维护通知").category
        )
        assertEquals(
            AiMessageClassifierCategory.STUDY,
            AiMessageClassifier.classifyText("复习考试笔记").category
        )
        assertEquals(
            AiMessageClassifierCategory.SOCIAL,
            AiMessageClassifier.classifyText("周末一起吃饭看电影").category
        )
    }

    @Test
    fun `classify cross category hits share confidence`() {
        // NOTICE 命中 2（notice/maintenance），TECH 命中 1（server）：2/3
        val result = AiMessageClassifier.classifyText("NOTICE: server maintenance")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(2.0 / 3.0, result.confidence, 0.001)
    }

    @Test
    fun `detect emotion angry and anxious lexicons`() {
        val angry = AiEmotionReply.detectEmotion(listOf("气死了烦死了"))
        assertEquals(Emotion.ANGRY, angry.emotion)
        assertEquals(1.0, angry.confidence, 0.0001)
        val anxious = AiEmotionReply.detectEmotion(listOf("焦虑失眠怎么办"))
        assertEquals(Emotion.ANXIOUS, anxious.emotion)
        assertEquals(1.0, anxious.confidence, 0.0001)
    }

    @Test
    fun `detect emotion confidence is share of hits across texts`() {
        // 哈哈(1) + 哈哈哈(2) = 3 happy，难过 = 1 sad：3/4
        val result = AiEmotionReply.detectEmotion(listOf("哈哈", "难过", "哈哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.75, result.confidence, 0.0001)
    }

    @Test
    fun `classify single keyword yields full confidence and wire model frozen`() {
        val result = AiMessageClassifier.classifyText("通知")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
        assertEquals("notice", AiMessageClassifierCategory.NOTICE.wire)
        assertEquals("ai_enhance_classify_notice", AiMessageClassifierCategory.NOTICE.labelKey)
        assertEquals("happy", Emotion.HAPPY.wire)
    }

    @Test
    fun `classify shared keyword ties to first max category`() {
        // 上线同时在 NOTICE 与 TECH 词典里：平局取枚举在前的 NOTICE。
        val result = AiMessageClassifier.classifyText("上线")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify three way share takes first max with third confidence`() {
        val result = AiMessageClassifier.classifyText("通知 任务 转账")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0 / 3.0, result.confidence, 0.001)
    }

    @Test
    fun `detect emotion overlapping happy hits count twice in one text`() {
        // 哈哈哈同时含 哈哈与哈哈哈两个词条：单条文本计 2 次命中，置信度仍为 1.0。
        val result = AiEmotionReply.detectEmotion(listOf("哈哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion window boundary splits the keyword`() {
        // 200 字符窗口切在 开心中间时不命中；恰好容纳时命中。
        val split = AiEmotionReply.detectEmotion(listOf("垫".repeat(199) + "开心"))
        assertEquals(Emotion.NEUTRAL, split.emotion)
        assertEquals(0.0, split.confidence, 0.0001)
        val whole = AiEmotionReply.detectEmotion(listOf("垫".repeat(198) + "开心"))
        assertEquals(Emotion.HAPPY, whole.emotion)
        assertEquals(1.0, whole.confidence, 0.0001)
    }

    @Test
    fun `classify window boundary splits the keyword`() {
        // 300 字符窗口切在 转账中间时不命中；恰好容纳时命中。
        val split = AiMessageClassifier.classifyText("垫".repeat(299) + "转账")
        assertEquals(AiMessageClassifierCategory.OTHER, split.category)
        assertEquals(0.2, split.confidence, 0.0001)
        val whole = AiMessageClassifier.classifyText("垫".repeat(298) + "转账")
        assertEquals(AiMessageClassifierCategory.FINANCE, whole.category)
        assertEquals(1.0, whole.confidence, 0.0001)
    }

    @Test
    fun `classify repeated keyword counts once`() {
        val result = AiMessageClassifier.classifyText("转账 转账 转账")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify english substrings hit finance lexicon`() {
        val result = AiMessageClassifier.classifyText("the refunded payment failed")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify single char study keyword`() {
        val result = AiMessageClassifier.classifyText("看书去")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion blank only list is neutral`() {
        val result = AiEmotionReply.detectEmotion(listOf("   ", ""))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion english share across texts`() {
        val result = AiEmotionReply.detectEmotion(listOf("love", "sad", "cry"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(2.0 / 3.0, result.confidence, 0.001)
    }

    @Test
    fun `classify multiword phrase entry counts once`() {
        // "follow up" 是 TODO 词典里的一条词条：出现两次仍只计 1 次命中。
        val result = AiMessageClassifier.classifyText("please follow up, follow up")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify two way tie takes enum first category`() {
        val result = AiMessageClassifier.classifyText("任务 转账")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify long english keyword at scan window edge`() {
        // 300 字符窗口恰好容纳 maintenance 时命中，多垫一字切掉末尾时不命中。
        val whole = AiMessageClassifier.classifyText("垫".repeat(289) + "maintenance")
        assertEquals(AiMessageClassifierCategory.NOTICE, whole.category)
        assertEquals(1.0, whole.confidence, 0.0001)
        val split = AiMessageClassifier.classifyText("垫".repeat(290) + "maintenance")
        assertEquals(AiMessageClassifierCategory.OTHER, split.category)
        assertEquals(0.2, split.confidence, 0.0001)
    }

    @Test
    fun `detect emotion english hits are case sensitive`() {
        // 情绪词典用不忽略大小写的 contains，与分类器的 ignoreCase 不同。
        val upper = AiEmotionReply.detectEmotion(listOf("LOVE", "HAPPY"))
        assertEquals(Emotion.NEUTRAL, upper.emotion)
        assertEquals(0.0, upper.confidence, 0.0001)
        val lower = AiEmotionReply.detectEmotion(listOf("love"))
        assertEquals(Emotion.HAPPY, lower.emotion)
        assertEquals(1.0, lower.confidence, 0.0001)
    }

    @Test
    fun `detect emotion single text tie picks first max`() {
        val result = AiEmotionReply.detectEmotion(listOf("开心 难过"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
        val reversed = AiEmotionReply.detectEmotion(listOf("难过 开心"))
        assertEquals(Emotion.HAPPY, reversed.emotion)
        assertEquals(0.5, reversed.confidence, 0.0001)
    }

    @Test
    fun `classify all finance keywords stay at full confidence`() {
        val result = AiMessageClassifier.classifyText("转账 账单 付款")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify repeated keyword still ties to first category`() {
        // 重复词只计一次：转账与部署仍是 1 比 1 平局，取枚举在前的 FINANCE。
        val result = AiMessageClassifier.classifyText("转账 转账 部署")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion share across texts favors sad majority`() {
        val result = AiEmotionReply.detectEmotion(listOf("开心", "难过", "难过"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(2.0 / 3.0, result.confidence, 0.001)
    }

    @Test
    fun `detect emotion per text window ignores far keyword in one text`() {
        // 首条文本的关键词在 200 字窗口外，不影响第二条文本的 SAD 满置信度。
        val result = AiEmotionReply.detectEmotion(listOf("垫".repeat(250) + "开心", "难过"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify keyword just inside scan window still counts`() {
        val result = AiMessageClassifier.classifyText("垫".repeat(297) + "转账")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

}
