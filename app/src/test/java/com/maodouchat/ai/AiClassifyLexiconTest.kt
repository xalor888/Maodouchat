package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiClassifyLexiconTest {

    @Test
    fun `tie breaks toward notice`() {
        // maxByOrNull 取首个最大值：entries 顺序里 NOTICE 在前
        val result = AiClassifyLexicon.classifyText("通知 deadline")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `english matching ignores case`() {
        val result = AiClassifyLexicon.classifyText("INVOICE")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `scan window truncates beyond 300 chars`() {
        // 关键词被垫到 300 字符窗口之外，不应命中
        val result = AiClassifyLexicon.classifyText("垫".repeat(350) + "转账")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `no hits falls back to other with low confidence`() {
        val result = AiClassifyLexicon.classifyText("随便聊聊天气")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `classification is deterministic`() {
        val text = "服务器部署完了，接口 bug 修复后发版本"
        val first = AiClassifyLexicon.classifyText(text)
        val second = AiClassifyLexicon.classifyText(text)
        assertEquals(first.category, second.category)
        assertEquals(first.confidence, second.confidence, 0.0001)
    }

    @Test
    fun `empty text falls back to other`() {
        val result = AiClassifyLexicon.classifyText("")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `keyword fully inside scan window still counts`() {
        // 298 个垫字 + "转账" 恰好 300 字符，关键词完整落在窗口内
        val result = AiClassifyLexicon.classifyText("垫".repeat(298) + "转账")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `confidence splits proportionally across categories`() {
        val result = AiClassifyLexicon.classifyText("转账 发票 通知")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `keyword as substring of longer word still matches`() {
        // contains 是子串匹配："debug" 里含 "bug"
        val result = AiClassifyLexicon.classifyText("debug 模式")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `repeated keyword counts once per dictionary word`() {
        // 按词典词计数，不按出现次数："转账转账" 只算 1 次命中
        val result = AiClassifyLexicon.classifyText("转账转账")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `todo category from chinese task words`() {
        val result = AiClassifyLexicon.classifyText("待办事项记得提交")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `study category from exam words`() {
        val result = AiClassifyLexicon.classifyText("考试复习笔记都准备好了")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `multi-word english phrase matches as one hit`() {
        // "follow up" 是词典里带空格的一整个词，只算 1 次命中
        val result = AiClassifyLexicon.classifyText("please follow up soon")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `keyword split by scan window edge does not count`() {
        // 299 个垫字 + "转账"：窗口只含 "转" 一个字，凑不成关键词
        val result = AiClassifyLexicon.classifyText("垫".repeat(299) + "转账")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `shared keyword tie breaks toward earlier category`() {
        // "上线" 同时在 NOTICE 与 TECH 词典里，并列时 entries 顺序在前的 NOTICE 胜
        val result = AiClassifyLexicon.classifyText("上线")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `social category from laughter and weekend words`() {
        val laughed = AiClassifyLexicon.classifyText("哈哈哈太好笑了")
        assertEquals(AiMessageClassifierCategory.SOCIAL, laughed.category)
        assertEquals(1.0, laughed.confidence, 0.0001)
        val weekend = AiClassifyLexicon.classifyText("周末去吃饭")
        assertEquals(AiMessageClassifierCategory.SOCIAL, weekend.category)
        assertEquals(1.0, weekend.confidence, 0.0001)
    }

    @Test
    fun `three-way tie breaks toward earliest category`() {
        val result = AiClassifyLexicon.classifyText("通知 任务 转账")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `all six categories tied breaks toward notice`() {
        val result = AiClassifyLexicon.classifyText("通知 任务 转账 学习 代码 哈哈")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0 / 6.0, result.confidence, 0.0001)
    }

    @Test
    fun `multi-word phrase needs both words adjacent`() {
        val repeated = AiClassifyLexicon.classifyText("follow up follow up")
        assertEquals(AiMessageClassifierCategory.TODO, repeated.category)
        assertEquals(1.0, repeated.confidence, 0.0001)
        val split = AiClassifyLexicon.classifyText("follow the plan")
        assertEquals(AiMessageClassifierCategory.OTHER, split.category)
        assertEquals(0.2, split.confidence, 0.0001)
        val reversed = AiClassifyLexicon.classifyText("up follow")
        assertEquals(AiMessageClassifierCategory.OTHER, reversed.category)
        assertEquals(0.2, reversed.confidence, 0.0001)
    }

    @Test
    fun `blank and beyond-window english fall back to other`() {
        val blank = AiClassifyLexicon.classifyText("   ")
        assertEquals(AiMessageClassifierCategory.OTHER, blank.category)
        assertEquals(0.2, blank.confidence, 0.0001)
        val pastWindow = AiClassifyLexicon.classifyText("a".repeat(301) + "invoice")
        assertEquals(AiMessageClassifierCategory.OTHER, pastWindow.category)
        assertEquals(0.2, pastWindow.confidence, 0.0001)
    }

@Test
    fun `finance study tie breaks toward finance`() {
        val result = AiClassifyLexicon.classifyText("转账 付款 学习 考试")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `english keyword exactly filling the scan window still counts`() {
        val result = AiClassifyLexicon.classifyText("a".repeat(293) + "invoice")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `the word other itself has no lexicon entry`() {
        val result = AiClassifyLexicon.classifyText("other")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `multi-word phrase matches ignoring case`() {
        val result = AiClassifyLexicon.classifyText("FOLLOW UP")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `dominant category confidence is its share of all hits`() {
        val result = AiClassifyLexicon.classifyText("代码 服务器 bug 学习")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        // TECH 词典里 "bug" 列了两次，两次都计命中，所以是 4/5 而不是 3/4。
        assertEquals(0.8, result.confidence, 0.0001)
    }

    @Test
    fun `tech category from deployment words`() {
        val result = AiClassifyLexicon.classifyText("部署服务器接口")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `finance category from english pay words`() {
        val result = AiClassifyLexicon.classifyText("pay the bill and refund")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `social category from english leisure words`() {
        val result = AiClassifyLexicon.classifyText("weekend movie trip")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `study category from english course words`() {
        val result = AiClassifyLexicon.classifyText("study course notes")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `notice category from maintenance words`() {
        val result = AiClassifyLexicon.classifyText("系统维护公告")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `duplicate dictionary word counts per listing`() {
        // "bug" 在 TECH 词典里列了两次，两次都计命中：TECH 2 票、STUDY 1 票
        val result = AiClassifyLexicon.classifyText("bug 学习")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `uppercase substring run matches two tech words`() {
        val result = AiClassifyLexicon.classifyText("BUGFIX")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `social category from english lol`() {
        val result = AiClassifyLexicon.classifyText("lol")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `finance category from chinese hongbao word`() {
        val result = AiClassifyLexicon.classifyText("收到红包")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `punctuation only falls back to other`() {
        val result = AiClassifyLexicon.classifyText("!!! ???")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `finance category from transfer and price words`() {
        val result = AiClassifyLexicon.classifyText("transfer price")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `study category from book word`() {
        val result = AiClassifyLexicon.classifyText("看书")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `multi-word phrase exactly filling the scan window still counts`() {
        // 291 个垫字 + "follow up"（9 字符）恰好 300 字符，短语完整落在窗口内
        val result = AiClassifyLexicon.classifyText("a".repeat(291) + "follow up")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `majority category confidence is its share with four hits`() {
        val result = AiClassifyLexicon.classifyText("转账 发票 报销 通知")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(0.75, result.confidence, 0.0001)
    }

    @Test
    fun `social category from good night phrase`() {
        val result = AiClassifyLexicon.classifyText("say good night")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `todo category from english assign word`() {
        val result = AiClassifyLexicon.classifyText("assign")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `notice category from english announcement word`() {
        val result = AiClassifyLexicon.classifyText("announcement")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `finance category from chinese salary word`() {
        val result = AiClassifyLexicon.classifyText("收到工资")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `tech category from english release word`() {
        val result = AiClassifyLexicon.classifyText("release")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `study category from english lecture word`() {
        val result = AiClassifyLexicon.classifyText("lecture")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }



    @Test
    fun `social category from chinese dinner word`() {
        val result = AiClassifyLexicon.classifyText("一起吃饭")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `tech category from chinese server word`() {
        val result = AiClassifyLexicon.classifyText("服务器挂了")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `todo category from chinese deadline and submit words`() {
        val result = AiClassifyLexicon.classifyText("截止前提交")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `finance category from chinese invoice word`() {
        val result = AiClassifyLexicon.classifyText("发票抬头")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `study category from chinese notes word`() {
        val result = AiClassifyLexicon.classifyText("复习笔记")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }


    @Test
    fun `todo category from chinese weekly meeting words`() {
        val result = AiClassifyLexicon.classifyText("周会记得提交")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `finance share beats todo on reimbursement invoice and submit`() {
        val result = AiClassifyLexicon.classifyText("报销发票已提交")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `tech category from chinese database and version words`() {
        val result = AiClassifyLexicon.classifyText("数据库版本升级")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `study category from chinese thesis and reading words`() {
        val result = AiClassifyLexicon.classifyText("论文阅读做完")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `notice category from attention and shared launch words`() {
        val result = AiClassifyLexicon.classifyText("请注意系统上线")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        // “上线”同时命中 NOTICE 与 TECH 词典，NOTICE 2 票对 TECH 1 票。
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `overlapping laughter entries inflate social share`() {
        // "哈哈哈" 同时命中 "哈哈" 与 "哈哈哈" 两条词典，社交 2 票对财务 1 票。
        val result = AiClassifyLexicon.classifyText("哈哈哈 转账")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `study ties tech on duplicate bug votes and wins by order`() {
        // "bug" 在 TECH 词典列两次得 2 票，学习 2 票打平，entries 顺序里学习在前。
        val result = AiClassifyLexicon.classifyText("bug 考试 复习")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `notice ties tech on distinct words and wins by order`() {
        val result = AiClassifyLexicon.classifyText("系统维护 服务器")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `todo ties finance on phrase and invoice and wins by order`() {
        val result = AiClassifyLexicon.classifyText("follow up invoice")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `english phrase split by scan window edge does not count`() {
        // 297 个垫字 + "fol"：短语被 300 字符窗口拦腰切断，凑不成词典短语。
        val result = AiClassifyLexicon.classifyText("a".repeat(297) + "follow up")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `tech beats finance three to two with proportional confidence`() {
        val result = AiClassifyLexicon.classifyText("代码 服务器 数据库 转账 发票")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(3.0 / 5.0, result.confidence, 0.0001)
    }

    @Test
    fun `finance ties tech on duplicate bug votes and wins by order`() {
        // "bug" 在 TECH 词典列两次得 2 票，财务 2 票打平，entries 顺序里财务在前。
        val result = AiClassifyLexicon.classifyText("refund price bug")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `notice ties social and wins by order`() {
        val result = AiClassifyLexicon.classifyText("公告 哈哈")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `double spaced phrase does not match todo`() {
        // 词典短语是单空格 "follow up"，双空格凑不成该词，其余单字也不是词典词。
        val result = AiClassifyLexicon.classifyText("follow  up")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `three categories finance study social tie breaks toward finance`() {
        val result = AiClassifyLexicon.classifyText("考试 转账 哈哈")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `finance category from chinese balance word`() {
        val result = AiClassifyLexicon.classifyText("余额不足")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `social category from chinese morning greeting`() {
        val result = AiClassifyLexicon.classifyText("早安呀")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `study category from chinese homework word`() {
        val result = AiClassifyLexicon.classifyText("作业写完了")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `notice category from english reminder word`() {
        val result = AiClassifyLexicon.classifyText("reminder")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `tech category from duplicate bug listing alone`() {
        // "bug" 在 TECH 词典里列了两次，单字输入也计 2 次命中，置信度仍为 1.0。
        val result = AiClassifyLexicon.classifyText("bug")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `notice category from english maintenance word`() {
        val result = AiClassifyLexicon.classifyText("maintenance")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `social category from english good night phrase`() {
        val result = AiClassifyLexicon.classifyText("good night everyone")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `study ties tech on lecture and database words and wins by order`() {
        val result = AiClassifyLexicon.classifyText("lecture database")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `finance category from uppercase english invoice word`() {
        // contains 忽略大小写，大写英文词同样命中。
        val result = AiClassifyLexicon.classifyText("INVOICE")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `keyword beyond scan window is ignored`() {
        // 300 字符窗口外才出现的完整关键词不计入，整条判 OTHER。
        val result = AiClassifyLexicon.classifyText("x".repeat(300) + "invoice")
        assertEquals(AiMessageClassifierCategory.OTHER, result.category)
        assertEquals(0.2, result.confidence, 0.0001)
    }

    @Test
    fun `notice category from chinese system message phrase`() {
        val result = AiClassifyLexicon.classifyText("系统消息")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `todo category from chinese follow up word`() {
        val result = AiClassifyLexicon.classifyText("跟进")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `tech category from chinese fix word`() {
        val result = AiClassifyLexicon.classifyText("修复")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `study category from chinese review word`() {
        val result = AiClassifyLexicon.classifyText("复习")
        assertEquals(AiMessageClassifierCategory.STUDY, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `social category from chinese party word`() {
        val result = AiClassifyLexicon.classifyText("聚会")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }


    @Test
    fun `notice category from chinese change word`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.NOTICE, 1.0),
            AiClassifyLexicon.classifyText("变更")
        )
    }

    @Test
    fun `notice category from chinese reminder word`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.NOTICE, 1.0),
            AiClassifyLexicon.classifyText("提醒")
        )
    }

    @Test
    fun `study category from chinese course word`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.STUDY, 1.0),
            AiClassifyLexicon.classifyText("课程")
        )
    }

    @Test
    fun `social category from chinese movie and travel words`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.SOCIAL, 1.0),
            AiClassifyLexicon.classifyText("电影旅行")
        )
    }

    @Test
    fun `notice ties study on change and course words and wins by order`() {
        // 平局取 entries 顺序在前的类目：NOTICE 在 STUDY 之前
        assertEquals(
            Classification(AiMessageClassifierCategory.NOTICE, 0.5),
            AiClassifyLexicon.classifyText("变更课程")
        )
    }


    @Test
    fun `notice category from english notice word alone`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.NOTICE, 1.0),
            AiClassifyLexicon.classifyText("notice")
        )
    }

    @Test
    fun `finance category from chinese payment word alone`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.FINANCE, 1.0),
            AiClassifyLexicon.classifyText("请付款")
        )
    }

    @Test
    fun `todo category from chinese remember word alone`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.TODO, 1.0),
            AiClassifyLexicon.classifyText("记得带伞")
        )
    }

    @Test
    fun `tech category from chinese code word alone`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.TECH, 1.0),
            AiClassifyLexicon.classifyText("代码写完了")
        )
    }

    @Test
    fun `social category from english weekend word alone`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.SOCIAL, 1.0),
            AiClassifyLexicon.classifyText("weekend plans")
        )
    }


    @Test
    fun `shared launch word ties notice and tech toward notice`() {
        // “上线”同时命中 NOTICE 与 TECH，entries 顺序里 NOTICE 在前
        assertEquals(
            Classification(AiMessageClassifierCategory.NOTICE, 0.5),
            AiClassifyLexicon.classifyText("系统上线了")
        )
    }

    @Test
    fun `triple laughter counts both laughter dictionary entries`() {
        // “哈哈哈”同时含“哈哈”与“哈哈哈”两条词典词，算 2 次命中
        assertEquals(
            Classification(AiMessageClassifierCategory.SOCIAL, 1.0),
            AiClassifyLexicon.classifyText("哈哈哈")
        )
    }

    @Test
    fun `english invoice and bill words share finance at full confidence`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.FINANCE, 1.0),
            AiClassifyLexicon.classifyText("INVOICE BILL")
        )
    }

    @Test
    fun `study lecture and course words share study at full confidence`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.STUDY, 1.0),
            AiClassifyLexicon.classifyText("lecture course")
        )
    }

    @Test
    fun `finance study tech three-way tie breaks toward finance`() {
        // TECH 词典里 "bug" 出现两次会算 2 次命中，凑不成平局，改用单次词 "代码"。
        assertEquals(
            Classification(AiMessageClassifierCategory.FINANCE, 1.0 / 3.0),
            AiClassifyLexicon.classifyText("转账 考试 代码")
        )
    }

    @Test
    fun `finance category from chinese receipt word`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.FINANCE, 1.0),
            AiClassifyLexicon.classifyText("收款")
        )
    }

    @Test
    fun `social category from chinese good night word`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.SOCIAL, 1.0),
            AiClassifyLexicon.classifyText("晚安")
        )
    }

    @Test
    fun `todo category from chinese arrange word`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.TODO, 1.0),
            AiClassifyLexicon.classifyText("安排一下")
        )
    }

    @Test
    fun `study category from chinese learn word`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.STUDY, 1.0),
            AiClassifyLexicon.classifyText("学习时间")
        )
    }

    @Test
    fun `tech category from english api word`() {
        assertEquals(
            Classification(AiMessageClassifierCategory.TECH, 1.0),
            AiClassifyLexicon.classifyText("call the api")
        )
    }


    @Test
    fun `partial lexicon phrase alone does not hit`() {
        val bare = AiClassifyLexicon.classifyText("注意")
        assertEquals(AiMessageClassifierCategory.OTHER, bare.category)
        assertEquals(0.2, bare.confidence, 0.0001)
        val full = AiClassifyLexicon.classifyText("请注意")
        assertEquals(AiMessageClassifierCategory.NOTICE, full.category)
        assertEquals(1.0, full.confidence, 0.0001)
    }

    @Test
    fun `bug substring inside debug counts as tech`() {
        val result = AiClassifyLexicon.classifyText("debug")
        assertEquals(AiMessageClassifierCategory.TECH, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `priceless contains price and counts as finance`() {
        val result = AiClassifyLexicon.classifyText("priceless")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `single char package alone does not hit finance`() {
        val bare = AiClassifyLexicon.classifyText("包")
        assertEquals(AiMessageClassifierCategory.OTHER, bare.category)
        assertEquals(0.2, bare.confidence, 0.0001)
        val full = AiClassifyLexicon.classifyText("红包")
        assertEquals(AiMessageClassifierCategory.FINANCE, full.category)
        assertEquals(1.0, full.confidence, 0.0001)
    }

    @Test
    fun `phrase split by middle word does not hit todo`() {
        val split = AiClassifyLexicon.classifyText("follow me up")
        assertEquals(AiMessageClassifierCategory.OTHER, split.category)
        assertEquals(0.2, split.confidence, 0.0001)
        val joined = AiClassifyLexicon.classifyText("follow up")
        assertEquals(AiMessageClassifierCategory.TODO, joined.category)
        assertEquals(1.0, joined.confidence, 0.0001)
    }

    @Test
    fun `english follow up phrase and deadline share todo at full confidence`() {
        val result = AiClassifyLexicon.classifyText("follow up deadline")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `trip word alone hits social at full confidence`() {
        val result = AiClassifyLexicon.classifyText("trip")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `uppercase maintenance hits notice at full confidence`() {
        val result = AiClassifyLexicon.classifyText("MAINTENANCE")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `notice and todo tie breaks toward notice with half confidence`() {
        val result = AiClassifyLexicon.classifyText("通知 任务")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `announcements plural still hits notice via substring`() {
        val result = AiClassifyLexicon.classifyText("announcements")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `discount word alone hits finance at full confidence`() {
        val result = AiClassifyLexicon.classifyText("优惠")
        assertEquals(AiMessageClassifierCategory.FINANCE, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `do not forget phrase alone hits todo at full confidence`() {
        val result = AiClassifyLexicon.classifyText("别忘了")
        assertEquals(AiMessageClassifierCategory.TODO, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `notice finance study three-way tie breaks toward notice with third confidence`() {
        val result = AiClassifyLexicon.classifyText("通知 转账 学习")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(1.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `finance and notice two-two tie breaks toward notice with half confidence`() {
        val result = AiClassifyLexicon.classifyText("转账 付款 通知 公告")
        assertEquals(AiMessageClassifierCategory.NOTICE, result.category)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `english dinner word alone hits social at full confidence`() {
        val result = AiClassifyLexicon.classifyText("dinner")
        assertEquals(AiMessageClassifierCategory.SOCIAL, result.category)
        assertEquals(1.0, result.confidence, 0.0001)
    }
}
