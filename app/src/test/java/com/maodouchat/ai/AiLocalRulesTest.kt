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

    @Test
    fun `classify deploy and course keywords at full confidence`() {
        val tech = AiMessageClassifier.classifyText("部署")
        assertEquals(AiMessageClassifierCategory.TECH, tech.category)
        assertEquals(1.0, tech.confidence, 0.0001)
        val study = AiMessageClassifier.classifyText("课程")
        assertEquals(AiMessageClassifierCategory.STUDY, study.category)
        assertEquals(1.0, study.confidence, 0.0001)
    }

    @Test
    fun `detect emotion great and sad hits are case sensitive`() {
        val upper = AiEmotionReply.detectEmotion(listOf("GREAT", "SAD"))
        assertEquals(Emotion.NEUTRAL, upper.emotion)
        assertEquals(0.0, upper.confidence, 0.0001)
        val great = AiEmotionReply.detectEmotion(listOf("great"))
        assertEquals(Emotion.HAPPY, great.emotion)
        assertEquals(1.0, great.confidence, 0.0001)
        val sad = AiEmotionReply.detectEmotion(listOf("sad"))
        assertEquals(Emotion.SAD, sad.emotion)
        assertEquals(1.0, sad.confidence, 0.0001)
    }

    @Test
    fun `classify follow up phrase plus deadline combine`() {
        val result = AiMessageClassifier.classifyText("please follow up before deadline")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion sad wins tie over anxious`() {
        val result = AiEmotionReply.detectEmotion(listOf("担心 难过"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify single char keyword at exact scan boundary`() {
        val whole = AiMessageClassifier.classifyText("垫".repeat(299) + "书")
        assertEquals(AiMessageClassifierCategory.STUDY, whole.category)
        assertEquals(1.0, whole.confidence, 0.0001)
        val split = AiMessageClassifier.classifyText("垫".repeat(300) + "书")
        assertEquals(AiMessageClassifierCategory.OTHER, split.category)
        assertEquals(0.2, split.confidence, 0.0001)
    }


    @Test
    fun `classify red packet and coupon hits stay finance`() {
        val result = AiMessageClassifier.classifyText("红包 优惠")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify english social words hit social`() {
        val result = AiMessageClassifier.classifyText("haha lol weekend")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion chinese praise words hit happy`() {
        val one = AiEmotionReply.detectEmotion(listOf("太好了 棒"))
        assertEquals(Emotion.HAPPY, one.emotion)
        assertEquals(1.0, one.confidence, 0.0001)
        val two = AiEmotionReply.detectEmotion(listOf("太好了", "棒"))
        assertEquals(Emotion.HAPPY, two.emotion)
        assertEquals(1.0, two.confidence, 0.0001)
    }

    @Test
    fun `detect emotion angry wins tie over anxious`() {
        val result = AiEmotionReply.detectEmotion(listOf("生气 担心"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
        val reversed = AiEmotionReply.detectEmotion(listOf("担心 生气"))
        assertEquals(Emotion.ANGRY, reversed.emotion)
        assertEquals(0.5, reversed.confidence, 0.0001)
    }

    @Test
    fun `classify exam keyword at scan window edge`() {
        val whole = AiMessageClassifier.classifyText("垫".repeat(298) + "考试")
        assertEquals(AiMessageClassifierCategory.STUDY, whole.category)
        assertEquals(1.0, whole.confidence, 0.0001)
        val split = AiMessageClassifier.classifyText("垫".repeat(299) + "考试")
        assertEquals(AiMessageClassifierCategory.OTHER, split.category)
        assertEquals(0.2, split.confidence, 0.0001)
    }

    @Test
    fun `classify shared keyword tie picks notice over tech`() {
        // “上线”同时在通知与技术词典里，并列时取枚举在前的通知。
        val result = AiMessageClassifier.classifyText("上线了")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify english notice keywords combine at full confidence`() {
        val result = AiMessageClassifier.classifyText("ANNOUNCEMENT: maintenance")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify finance and notice tie picks notice with half confidence`() {
        val result = AiMessageClassifier.classifyText("转账通知")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion happy wins tie over angry across texts`() {
        val result = AiEmotionReply.detectEmotion(listOf("开心", "气死"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion mixed case english hits are missed`() {
        // 情绪词典 contains 不忽略大小写，首字母大写命中不了小写词条。
        val result = AiEmotionReply.detectEmotion(listOf("Love", "Happy"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }


    @Test
    fun `classify tech and social tie picks tech with half confidence`() {
        val result = AiMessageClassifier.classifyText("部署 周末")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify study beats finance with more hits`() {
        val result = AiMessageClassifier.classifyText("学习 课程 转账")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify all social keywords stay at full confidence`() {
        val result = AiMessageClassifier.classifyText("哈哈 周末 电影")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify notice beats todo with more hits`() {
        val result = AiMessageClassifier.classifyText("通知 公告 任务")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify english study words combine at full confidence`() {
        val result = AiMessageClassifier.classifyText("study homework exam")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify haha triple counts both haha forms at full confidence`() {
        val result = AiMessageClassifier.classifyText("哈哈哈")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify fix bug triple counts bug twice`() {
        val result = AiMessageClassifier.classifyText("fix bug")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify tie notice before todo takes notice`() {
        val result = AiMessageClassifier.classifyText("通知 任务")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify keyword exactly at window edge is cut`() {
        val result = AiMessageClassifier.classifyText("垫".repeat(300) + "转账")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `classify mixed language finance words combine`() {
        val result = AiMessageClassifier.classifyText("invoice 付款")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `remaining category wire and label keys are frozen`() {
        assertEquals("todo", AiMessageClassifierCategory.TODO.wire)
        assertEquals("ai_enhance_classify_todo", AiMessageClassifierCategory.TODO.labelKey)
        assertEquals("finance", AiMessageClassifierCategory.FINANCE.wire)
        assertEquals("ai_enhance_classify_finance", AiMessageClassifierCategory.FINANCE.labelKey)
        assertEquals("study", AiMessageClassifierCategory.STUDY.wire)
        assertEquals("ai_enhance_classify_study", AiMessageClassifierCategory.STUDY.labelKey)
        assertEquals("tech", AiMessageClassifierCategory.TECH.wire)
        assertEquals("ai_enhance_classify_tech", AiMessageClassifierCategory.TECH.labelKey)
        assertEquals("social", AiMessageClassifierCategory.SOCIAL.wire)
        assertEquals("ai_enhance_classify_social", AiMessageClassifierCategory.SOCIAL.labelKey)
        assertEquals("other", AiMessageClassifierCategory.OTHER.wire)
        assertEquals("ai_enhance_classify_other", AiMessageClassifierCategory.OTHER.labelKey)
    }

    @Test
    fun `classify refund and price combine at full confidence`() {
        val result = AiMessageClassifier.classifyText("refund price")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify assign task deadline combine at full confidence`() {
        val result = AiMessageClassifier.classifyText("please assign this task before deadline")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion miss and regret hits are sad`() {
        val result = AiEmotionReply.detectEmotion(listOf("miss you, 后悔"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion worried nervous stress hits are anxious`() {
        val result = AiEmotionReply.detectEmotion(listOf("worried nervous stress"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify uppercase english finance words match case insensitively`() {
        val result = AiMessageClassifier.classifyText("PAY BILL")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify shared online word ties to notice before tech`() {
        // 上线同时在通知与技术词典里：同分时取枚举在前的通知，置信度为 1/2。
        val result = AiMessageClassifier.classifyText("上线")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify finance study tie takes finance`() {
        val result = AiMessageClassifier.classifyText("转账 学习")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify keyword at scan edge is kept only when it fits`() {
        val kept = AiMessageClassifier.classifyText("垫".repeat(298) + "转账")
        assertEquals(AiMessageClassifierCategory.FINANCE, kept.category)
        assertEquals(1.0, kept.confidence, 0.0001)
        val cut = AiMessageClassifier.classifyText("垫".repeat(299) + "转账")
        assertEquals(AiMessageClassifierCategory.OTHER, cut.category)
        assertEquals(0.2, cut.confidence, 0.0001)
    }

    @Test
    fun `classify notice wire and label keys are frozen`() {
        assertEquals("notice", AiMessageClassifierCategory.NOTICE.wire)
        assertEquals("ai_enhance_classify_notice", AiMessageClassifierCategory.NOTICE.labelKey)
    }

    @Test
    fun `classify notice and todo tie takes notice`() {
        val result = AiMessageClassifier.classifyText("通知 任务")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify repeated keyword still counts once`() {
        val result = AiMessageClassifier.classifyText("转账转账")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `classify substring inside longer word still hits`() {
        val result = AiMessageClassifier.classifyText("billboard")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion happy sad tie takes happy`() {
        val result = AiEmotionReply.detectEmotion(listOf("哈哈 难过"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion keyword beyond scan window is ignored`() {
        val result = AiEmotionReply.detectEmotion(listOf("垫".repeat(250) + "哈哈"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }


    @Test
    fun `classify todo and finance tie takes todo`() {
        val result = AiMessageClassifier.classifyText("任务 转账")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify study and tech tie takes study`() {
        val result = AiMessageClassifier.classifyText("课程 部署")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `classify social and todo tie takes todo`() {
        val result = AiMessageClassifier.classifyText("聚会 任务")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion happy anxious tie takes happy`() {
        val result = AiEmotionReply.detectEmotion(listOf("哈哈 焦虑"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `detect emotion love cry tie takes happy`() {
        val result = AiEmotionReply.detectEmotion(listOf("love cry"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

}
