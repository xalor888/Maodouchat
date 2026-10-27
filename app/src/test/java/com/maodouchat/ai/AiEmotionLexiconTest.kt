package com.maodouchat.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiEmotionLexiconTest {

    @Test
    fun `tie breaks toward first listed emotion`() {
        // maxByOrNull 取首个最大值：happy 先于 sad
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "难过"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `hits aggregate across texts with confidence share`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "难过 哭了"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `scan window truncates beyond 200 chars`() {
        // 关键词被垫到 200 字符窗口之外，不应命中
        val result = AiEmotionLexicon.detectEmotion(listOf("垫".repeat(300) + "哈哈"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `overlapping dictionary words count per word`() {
        // 「哈哈哈哈」同时含「哈哈」「哈哈哈」，按词计数 2 次
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `english matching is case sensitive`() {
        assertEquals(Emotion.NEUTRAL, AiEmotionLexicon.detectEmotion(listOf("LOL GREAT")).emotion)
        assertEquals(Emotion.HAPPY, AiEmotionLexicon.detectEmotion(listOf("lol great")).emotion)
    }

    @Test
    fun `empty input yields neutral`() {
        val result = AiEmotionLexicon.detectEmotion(emptyList())
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `blank text yields neutral`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("   "))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `keyword fully inside scan window still counts`() {
        // 198 个垫字 + "哈哈" 恰好 200 字符，关键词完整落在窗口内
        val result = AiEmotionLexicon.detectEmotion(listOf("垫".repeat(198) + "哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `anxious words are detected`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("我很焦虑，压力好大"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `cross valence tie breaks toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "气死我了"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `angry words aggregate across overlapping entries`() {
        // 「烦死了」同时命中「烦死了」与「烦」，按词计数 3 次
        val result = AiEmotionLexicon.detectEmotion(listOf("气死我了，烦死了"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `english happy words aggregate`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("so happy love it"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `sad aggregates across separate texts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("难过", "哭了"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `keyword split by scan window edge does not count`() {
        // 199 个垫字 + "哈哈"：窗口只含 "哈" 一个字，凑不成关键词
        val result = AiEmotionLexicon.detectEmotion(listOf("垫".repeat(199) + "哈哈"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `scan window applies per text not across the list`() {
        // 首条文本的关键词在窗口外不命中，但不影响第二条文本的命中
        val result = AiEmotionLexicon.detectEmotion(listOf("垫".repeat(300) + "哈哈", "难过"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `three way tie breaks toward first listed emotion`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "难过", "生气"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `sad versus anxious tie breaks toward sad`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("难过", "焦虑"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `repeated occurrence of one word counts once`() {
        // 按词典词条 presence 计数：同一词出现多次仍只计 1 次
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈 哈哈 哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `empty string element yields neutral`() {
        val result = AiEmotionLexicon.detectEmotion(listOf(""))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `anxious english words aggregate`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("so worried nervous"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `angry versus anxious tie breaks toward angry`() {
        // 词典顺序 happy、sad、angry、anxious：maxByOrNull 取首个最大值
        val result = AiEmotionLexicon.detectEmotion(listOf("生气", "焦虑"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `single character angry word counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("滚"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `mixed valence confidence is the winner share`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "难过", "哭了", "伤心"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(3.0 / 4.0, result.confidence, 0.0001)
    }

    @Test
    fun `empty element does not dilute confidence`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("", "哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `english sad word miss counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("i miss you"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }


    @Test
    fun `anxious versus happy tie breaks toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("开心", "焦虑"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `two hit tie between happy and sad breaks toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈 开心", "难过 伤心"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `same word in two texts counts twice`() {
        // 词典词按文本逐条计数：同一词出现在两条文本里计 2 次
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `uppercase sad word stays neutral`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("I am SAD"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `anxious beats sad with more hits`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("焦虑 担心 害怕", "难过"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(0.75, result.confidence, 0.0001)
    }

    @Test
    fun `anxious english stress word counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("under stress"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `sad english cry and emo aggregate`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("cry emo"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `angry single char quarrel word counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("吵"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `four emotions tie breaks toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈 难过 生气 焦虑"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.25, result.confidence, 0.0001)
    }

    @Test
    fun `english love word exactly filling the scan window still counts`() {
        // 196 个垫字 + "love"（4 字符）恰好 200 字符，关键词完整落在窗口内
        val result = AiEmotionLexicon.detectEmotion(listOf("a".repeat(196) + "love"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `happy english haha word counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("haha"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `sad regret word counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("后悔"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `angry hate word counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("hate"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `anxious insomnia word counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("失眠"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `uppercase english happy word stays neutral`() {
        // 英文匹配大小写敏感：大写 HAPPY 不命中词典
        val result = AiEmotionLexicon.detectEmotion(listOf("HAPPY"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }



    @Test
    fun `sad word embedded mid-sentence counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("今天真的很难过"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `angry english mad word counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("so mad right now"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `two happy words in one text keep full confidence`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈 开心"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `three-char english word exactly filling the scan window still counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("a".repeat(197) + "mad"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `empty list and blank-only list give identical results`() {
        assertEquals(
            AiEmotionLexicon.detectEmotion(emptyList()),
            AiEmotionLexicon.detectEmotion(listOf("   "))
        )
    }


    @Test
    fun `english substring inside longer word counts`() {
        // contains 是子串匹配：lovely 含 love，照样命中
        val result = AiEmotionLexicon.detectEmotion(listOf("lovely day"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `chinese substring inside longer phrase counts`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("我很喜欢这个"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `sad beats anxious with more hits`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("难过 哭了", "担心"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `angry majority wins across all four emotions`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("气死", "哈哈", "难过", "担心", "生气"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(2.0 / 5.0, result.confidence, 0.0001)
    }

    @Test
    fun `blank element between opposing hits keeps tie toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "   ", "难过"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }


    @Test
    fun `anxious beats angry with more hits`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("焦虑 担心", "生气"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `happy versus anxious single word tie breaks toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "失眠"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `english substring hateful counts as angry`() {
        // contains 是子串匹配：hateful 含 hate，照样命中
        val result = AiEmotionLexicon.detectEmotion(listOf("hateful words"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `anxious english worried and anxious aggregate`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("worried anxious"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `sad majority over happy and angry shares`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("难过 伤心", "哈哈", "生气"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `sad three hits beat happy two hits`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("难过 伤心 哭了", "哈哈 开心"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(0.6, result.confidence, 0.0001)
    }

    @Test
    fun `sad angry anxious three way tie breaks toward sad`() {
        // maxByOrNull 取首个最大值：sad 排在 angry 与 anxious 之前
        val result = AiEmotionLexicon.detectEmotion(listOf("难过", "生气", "焦虑"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `uppercase english words across valences stay neutral`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("HAPPY SAD ANGRY"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `two different happy words across two texts keep full confidence`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "开心"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `anxious english nervous and worried aggregate`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("nervous worried"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }


    @Test
    fun `unlisted happy words giggle and cheer count`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("嘻嘻 耶"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `unlisted angry words detestable and disgusting count`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("可恶 恶心"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `unlisted anxious words running late and what to do count`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("来不及了怎么办"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `unlisted sad words regretful and let down count`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("遗憾 失落"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `negated happy word still counts by substring`() {
        // contains 是子串匹配：否定词里的「开心」照样命中，不做语义否定
        val result = AiEmotionLexicon.detectEmotion(listOf("我不开心"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `four way single hit tie breaks toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "难过", "生气", "担心"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.25, result.confidence, 0.0001)
    }

    @Test
    fun `same word repeated in one text counts once`() {
        // 按词典词计数不按出现次数：同一文本里两个「哈哈」只算 1 次，与「难过」打平
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈 哈哈", "难过"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `sad overlapping nan guo nan shou counts twice`() {
        // 「难过难受」同时含「难过」与「难受」，按词计数 2 次
        val result = AiEmotionLexicon.detectEmotion(listOf("难过难受"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `angry overlapping fan si le counts twice`() {
        // 「烦死了」同时含「烦死了」与「烦」，按词计数 2 次
        val result = AiEmotionLexicon.detectEmotion(listOf("烦死了"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `window cut splits keyword at boundary`() {
        // 200 字窗口把「哈哈」拦腰截成单字「哈」，不成词，不命中
        val result = AiEmotionLexicon.detectEmotion(listOf("垫".repeat(199) + "哈哈"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `single char bang word hits happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("棒"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `single angry word hits angry`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("愤怒"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `single nervous word hits anxious`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("紧张"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `single good news phrase hits happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("太好了"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `happy angry anxious three way tie breaks toward happy`() {
        // happy/sad/angry/anxious 枚举序：三方平局取首列 happy
        val result = AiEmotionLexicon.detectEmotion(listOf("棒", "愤怒", "紧张"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `english miss word hits sad`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("miss you"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `english stress word hits anxious`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("under stress"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `english emo word hits sad`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("feeling emo"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `sad angry tie breaks toward sad`() {
        // maxByOrNull 取首个最大值：sad 在 angry 之前
        val result = AiEmotionLexicon.detectEmotion(listOf("难过", "生气"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `angry anxious tie breaks toward angry`() {
        // 同理：angry 在 anxious 之前
        val result = AiEmotionLexicon.detectEmotion(listOf("生气", "担心"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }


    @Test
    fun `haha and great in one text both count toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("haha great"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `plain neutral sentence without dictionary words stays neutral`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("今天天气不错"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `anxious words across two texts aggregate to full confidence`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("我很担心", "压力好大"))
        assertEquals(Emotion.ANXIOUS, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `sad majority across texts keeps two thirds share`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "哭了", "哭了"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `chinese keyword ending exactly at scan window edge counts`() {
        // 200 字窗口恰好包住末尾的「哈哈」
        val result = AiEmotionLexicon.detectEmotion(listOf("垫".repeat(198) + "哈哈"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `happy and anxious two-way tie with different words breaks toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("开心", "担心"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `sad two hits beat anxious one hit in a single text`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("难过 伤心 担心"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(2.0 / 3.0, result.confidence, 0.0001)
    }

    @Test
    fun `angry word in second text only decides when first text is neutral`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("今天天气不错", "生气"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `uppercase lol alone stays neutral`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("LOL"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `three texts with one hit each tie toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈", "难过", "担心"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0 / 3.0, result.confidence, 0.0001)
    }


    @Test
    fun `english love and hate tie breaks toward happy`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("love hate"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(0.5, result.confidence, 0.0001)
    }

    @Test
    fun `happy three hits beat sad two hits at three fifths`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("哈哈 开心 喜欢", "难过 伤心"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(3.0 / 5.0, result.confidence, 0.0001)
    }

    @Test
    fun `angry three hits beat anxious two hits at three fifths`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("生气 愤怒 可恶", "担心 害怕"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(3.0 / 5.0, result.confidence, 0.0001)
    }

    @Test
    fun `uppercase lol plus lowercase love counts only love`() {
        // 词典匹配区分大小写：大写 LOL 不命中，只有小写 love 计 1 次
        val result = AiEmotionLexicon.detectEmotion(listOf("LOL love"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `happy four hits beat sad one hit at four fifths`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("难过", "哈哈 开心 喜欢 太好了"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(4.0 / 5.0, result.confidence, 0.0001)
    }

    @Test
    fun `sadness contains sad substring counts as sad`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("sadness"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `crying contains cry substring counts as sad`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("crying"))
        assertEquals(Emotion.SAD, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `madness contains mad substring counts as angry`() {
        val result = AiEmotionLexicon.detectEmotion(listOf("madness"))
        assertEquals(Emotion.ANGRY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }

    @Test
    fun `worry without ed suffix stays neutral`() {
        // 词典里只有 worried，裸 worry 不命中
        val result = AiEmotionLexicon.detectEmotion(listOf("worry"))
        assertEquals(Emotion.NEUTRAL, result.emotion)
        assertEquals(0.0, result.confidence, 0.0001)
    }

    @Test
    fun `hahaha contains haha counts once as happy`() {
        // 按词计次而非按出现次数：haha 只计 1 次
        val result = AiEmotionLexicon.detectEmotion(listOf("hahaha"))
        assertEquals(Emotion.HAPPY, result.emotion)
        assertEquals(1.0, result.confidence, 0.0001)
    }
}
