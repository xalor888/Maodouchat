package com.maodouchat.util

import com.maodouchat.data.model.MessageMeta
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G204b：`JsonFormat` 的 meta 标签编解码覆盖。被 12 个文件引用，此前零测试。
 *
 * 这里守两个**安全/可用性**性质：
 *
 * 1. **`composeContentWithMeta` 会先剥掉用户文本里的 meta 标签字面量**——
 *    否则攻击者（或误操作）能在消息正文里塞一个假 `<meta>...</meta>`，
 *    让收件方解析出**伪造的** replyToId / 转发来源 / 附件密钥。
 *    这不是理论问题：`MessageMeta` 里有 `attachmentKeyBase64`。
 * 2. **单字段损坏不得丢掉整段 meta**（8.49 修复）。
 *    注释写得很清楚：若整段回退为空，「含附件解密密钥」的 meta 会一起没，
 *    媒体永久无法解密。正确行为是**只丢坏的那一个字段**。
 */
class JsonFormatMetaTagTest {

    private val emptyMeta = MessageMeta()

    // ---- 默认值不产生 meta 标签 ----

    @Test
    fun allDefaultValuesProduceNoMetaTag() {
        val out = JsonFormat.composeContentWithMeta("hello", emptyMeta)
        assertEquals("hello", out, "全默认值时不应附加 meta 标签")
    }

    @Test
    fun blankTextWithDefaultMetaStaysBlank() {
        assertEquals("", JsonFormat.composeContentWithMeta("", emptyMeta))
        assertEquals("   ", JsonFormat.composeContentWithMeta("   ", emptyMeta))
    }

    @Test
    fun aNonDefaultValueAttachesTheTag() {
        val out = JsonFormat.composeContentWithMeta("hello", MessageMeta(replyToId = "m1"))
        assertTrue(out.startsWith("hello"), "正文应原样在前")
        assertContains(out, "<meta>")
        assertTrue(out.endsWith("</meta>"), "meta 标签应收尾，实际 $out")
        assertContains(out, "m1")
    }

    // ---- 用户文本里的 meta 标签必须被剥掉 ----

    @Test
    fun metaTagLiteralsInUserTextAreStripped() {
        // 用户正文里塞一个「假 meta」——不能让它活到编码结果里
        val injected = "look <meta>{\"replyToId\":\"evil-message\"}</meta> at this"
        val out = JsonFormat.composeContentWithMeta(injected, MessageMeta(replyToId = "real"))
        assertFalse(out.contains("<meta>{\"replyToId\":\"evil"), "用户注入的 meta 不得残留，实际 $out")
        // 剥掉的是字面量本身，不是把正文删掉
        assertTrue(out.startsWith("look "), "正文其余部分应保留，实际 $out")
        assertTrue(out.contains(" at this"), "正文其余部分应保留，实际 $out")

        // 只有一个真 meta 标签（我们自己附加的那个）
        assertEquals(1, out.split("<meta>").size - 1, "结果里只应有一个 <meta>，实际 $out")
    }

    @Test
    fun metaTagLiteralsAreStrippedEvenWhenNoMetaIsAttached() {
        // 全默认值时返回 safeText，也要先剥字面量
        val out = JsonFormat.composeContentWithMeta("a<meta>b</meta>c", emptyMeta)
        assertEquals("abc", out, "全默认值时应返回剥干净后的正文，实际 $out")
    }

    // ---- 往返：messageMetaMap → encode → fromJsonString ----

    @Test
    fun metaRoundTripsThroughEncodeAndDecode() {
        val meta = MessageMeta(
            mentions = listOf("u1", "u2"),
            replyToId = "m-123",
            forwardedFrom = "Alice",
            voiceTranscript = "你好",
            voiceDurationMs = 4_200L,
            translations = mapOf("en" to "hello"),
            preferredTranslationLanguage = "en",
            fileName = "a.pdf",
            fileMimeType = "application/pdf",
            fileSizeBytes = 2_048L,
            attachmentId = "att_" + "a".repeat(30),
            attachmentKeyBase64 = "k".repeat(44),
            attachmentIvBase64 = "i".repeat(24),
            attachmentCipherSha256 = "c".repeat(64),
            attachmentPlainSha256 = "d".repeat(64),
            attachmentCipherSize = 2_112L,
            markdown = true,
            viewOnce = true,
            silent = true,
            spoilerMedia = true,
            forceReply = true,
        )
        val encoded = JsonFormat.encodeMessageMeta(meta)
        val back = JsonFormat.fromJsonString(encoded)
        assertEquals(meta.mentions, back.mentions, "mentions 往返应一致")
        assertEquals("m-123", back.replyToId)
        assertEquals("Alice", back.forwardedFrom, "9.143：forwardedFrom 必须配对往返")
        assertEquals(4_200L, back.voiceDurationMs)
        assertEquals(mapOf("en" to "hello"), back.translations)
        assertEquals("a.pdf", back.fileName)
        assertEquals(2_048L, back.fileSizeBytes)
        assertEquals("att_" + "a".repeat(30), back.attachmentId)
        // 最关键：附件解密密钥必须原样回来
        assertEquals("k".repeat(44), back.attachmentKeyBase64, "附件密钥往返丢失会让媒体永远解不开")
        assertEquals("i".repeat(24), back.attachmentIvBase64)
        assertEquals("c".repeat(64), back.attachmentCipherSha256)
        assertEquals("d".repeat(64), back.attachmentPlainSha256)
        assertEquals(2_112L, back.attachmentCipherSize)
        assertTrue(back.markdown && back.viewOnce && back.silent && back.spoilerMedia && back.forceReply)
    }

    @Test
    fun blankJsonYieldsDefaultMeta() {
        assertEquals(emptyMeta, JsonFormat.fromJsonString(""))
        assertEquals(emptyMeta, JsonFormat.fromJsonString("   "))
    }

    // ---- 8.49：单字段损坏只丢该字段 ----

    @Test
    fun oneCorruptFieldDoesNotDropTheDecryptionKey() {
        val good = JsonFormat.encodeMessageMeta(
            MessageMeta(
                replyToId = "m-1",
                attachmentId = "att_" + "a".repeat(30),
                attachmentKeyBase64 = "k".repeat(44),
                attachmentIvBase64 = "i".repeat(24),
            )
        )
        // 把 mentions 从数组改成字符串（类型混淆）——这一处损坏
        val corrupted = good.replace("\"mentions\":[]", "\"mentions\":\"not-an-array\"")
        assertTrue(corrupted != good, "应确实制造了一处类型损坏")

        val back = JsonFormat.fromJsonString(corrupted)
        // 坏字段丢掉
        assertEquals(emptyList(), back.mentions, "类型损坏的字段应回退为空")
        // 但其它字段（尤其附件密钥）必须还在
        assertEquals("k".repeat(44), back.attachmentKeyBase64, "8.49：单字段损坏不得丢附件密钥")
        assertEquals("i".repeat(24), back.attachmentIvBase64)
        assertEquals("att_" + "a".repeat(30), back.attachmentId)
        assertEquals("m-1", back.replyToId, "无关字段不应被牵连")
    }

    @Test
    fun objectFieldsDoNotThrowWhenTypeIsConfused() {
        // translations / aiImageAnalyses / aiFileAnalyses 用安全转型，
        // 把它们全换成字符串也不该抛，且只丢自己
        val good = JsonFormat.encodeMessageMeta(
            MessageMeta(
                translations = mapOf("en" to "hi"),
                aiImageAnalyses = mapOf("ocr" to "text"),
                aiFileAnalyses = mapOf("sum" to "x"),
                attachmentKeyBase64 = "k".repeat(44),
            )
        )
            .replace("\"translations\":{\"en\":\"hi\"}", "\"translations\":\"oops\"")
            .replace("\"aiImageAnalyses\":{\"ocr\":\"text\"}", "\"aiImageAnalyses\":123")
            .replace("\"aiFileAnalyses\":{\"sum\":\"x\"}", "\"aiFileAnalyses\":true")
        val back = JsonFormat.fromJsonString(good)
        assertEquals(emptyMap(), back.translations)
        assertEquals(emptyMap(), back.aiImageAnalyses)
        assertEquals(emptyMap(), back.aiFileAnalyses)
        assertEquals("k".repeat(44), back.attachmentKeyBase64, "容器字段损坏不得牵连附件密钥")
    }

    @Test
    fun nonStringScalarFieldsDoNotThrow() {
        // 布尔/长整字段是「取字符串再 strict 解析」，喂对象/数组也不该抛
        val payload = """{"markdown":{"a":1},"viewOnce":[1,2],"fileSizeBytes":{"x":1},"attachmentCipherSize":"notanumber"}"""
        val back = JsonFormat.fromJsonString(payload)
        assertFalse(back.markdown, "类型不符的布尔应回退 false")
        assertFalse(back.viewOnce)
        assertNull(back.fileSizeBytes, "类型不符的长整应回退 null")
        assertNull(back.attachmentCipherSize, "非数字字符串应回退 null")
    }

    // ---- inlineKeyboard：含 callback_data 兼容与长度截断 ----

    @Test
    fun inlineKeyboardReadsBothCallbackKeySpellings() {
        val snake = """{"inlineKeyboard":[[{"text":"OK","callback_data":"do_ok"}]]}"""
        val camel = """{"inlineKeyboard":[[{"text":"OK","callbackData":"do_ok"}]]}"""
        assertEquals("do_ok", JsonFormat.fromJsonString(snake).inlineKeyboard.first().first().callbackData)
        assertEquals("do_ok", JsonFormat.fromJsonString(camel).inlineKeyboard.first().first().callbackData)
    }

    @Test
    fun inlineKeyboardTruncatesAndDropsMalformedRows() {
        // 用转义拼，避免手写大 JSON 出错（第一版就是这么翻的车）
        val longText = "y".repeat(200)
        val longCallback = "d".repeat(200)
        val json = buildString {
            append("""{"inlineKeyboard":[""")
            append("""[{"text":"ok","callbackData":"c"}],""")          // 正常行
            append("""{"text":"$longText","callbackData":"$longCallback"},""") // 行位置放了个对象 → 丢
            append("""[{"text":"z","callbackData":"e"}]""")             // 正常行
            append("]}")
        }
        // 自造 payload 必须先确认它是合法 JSON
        kotlin.runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
        }.onFailure { throw AssertionError("测试自造的 JSON 不合法: ${it.message}", it) }
        val kb = JsonFormat.fromJsonString(json).inlineKeyboard
        // 第二「行」不是数组 → 整行丢弃；第三行保留
        assertEquals(2, kb.size, "畸形行应被丢弃，实际 $kb")
        val btn = kb.first().first()
        assertTrue(btn.text.length <= 64, "text 应截到 64，实际 ${btn.text.length}")
        assertTrue(btn.callbackData.length <= 128, "callbackData 应截到 128")
    }

    // ---- toJsonElement 的 type dispatch ----

    @Test
    fun toJsonElementDispatchesOnType() {
        assertEquals("null", JsonFormat.toJsonElement(null).toString())
        assertEquals("\"s\"", JsonFormat.toJsonElement("s").toString())
        assertEquals("42", JsonFormat.toJsonElement(42).toString())
        assertEquals("true", JsonFormat.toJsonElement(true).toString())
        assertEquals("[1,\"a\"]", JsonFormat.toJsonElement(listOf(1, "a")).toString())
        assertEquals("""{"a":1}""", JsonFormat.toJsonElement(mapOf("a" to 1)).toString())
        // 未知类型走 toString()
        assertEquals("\"ABC\"", JsonFormat.toJsonElement(SomeThing("ABC")).toString())
    }

    private class SomeThing(private val v: String) {
        override fun toString(): String = v
    }

    @Test
    fun encodeOfNestedStructuresIsStable() {
        val nested = mapOf(
            "list" to listOf(mapOf("k" to 1), "s", null),
            "num" to 3.5,
            "bool" to false,
        )
        assertEquals("""{"list":[{"k":1},"s",null],"num":3.5,"bool":false}""", JsonFormat.encode(nested))
        // pretty 变体只是加空格，内容等价
        assertTrue(JsonFormat.encodePretty(nested).contains("\"list\""))
    }

    @Test
    fun messageMetaMapContainsEveryFieldThatDecodeReads() {
        // 编码侧与解码侧必须配对：漏一个字段 = 该功能静默失效
        val encoded = JsonFormat.encodeMessageMeta(
            MessageMeta(
                mentions = listOf("u"),
                replyToId = "r",
                forwardedFrom = "f",
                voiceTranscript = "t",
                voiceDurationMs = 1L,
                translations = mapOf("en" to "hi"),
                preferredTranslationLanguage = "en",
                aiImageAnalyses = mapOf("o" to "t"),
                preferredImageAnalysisMode = "ocr",
                aiFileAnalyses = mapOf("s" to "x"),
                preferredFileAnalysisMode = "sum",
                aiFileLastQuestion = "q",
                aiAssisted = true,
                aiAssistantMode = "chat",
                fileName = "n",
                fileMimeType = "m",
                fileSizeBytes = 1L,
                attachmentId = "a",
                attachmentKeyBase64 = "k",
                attachmentIvBase64 = "v",
                attachmentCipherSha256 = "c",
                attachmentPlainSha256 = "p",
                attachmentCipherSize = 1L,
                markdown = true,
                viewOnce = true,
                viewOnceOpened = true,
                silent = true,
                spoilerMedia = true,
                spoilerRevealed = true,
                forceReply = true,
            )
        )
        val back = JsonFormat.fromJsonString(encoded)
        listOf(
            back.mentions, back.replyToId, back.forwardedFrom, back.voiceTranscript, back.voiceDurationMs,
            back.translations, back.preferredTranslationLanguage, back.aiImageAnalyses,
            back.preferredImageAnalysisMode, back.aiFileAnalyses, back.preferredFileAnalysisMode,
            back.aiFileLastQuestion, back.aiAssisted, back.aiAssistantMode, back.fileName,
            back.fileMimeType, back.fileSizeBytes, back.attachmentId, back.attachmentKeyBase64,
            back.attachmentIvBase64, back.attachmentCipherSha256, back.attachmentPlainSha256,
            back.attachmentCipherSize, back.markdown, back.viewOnce, back.viewOnceOpened,
            back.silent, back.spoilerMedia, back.spoilerRevealed, back.forceReply,
        ).forEachIndexed { idx, v ->
            assertNotNull(v, "第 $idx 个字段往返后为 null——编码/解码两侧漏配了")
        }
    }
}
