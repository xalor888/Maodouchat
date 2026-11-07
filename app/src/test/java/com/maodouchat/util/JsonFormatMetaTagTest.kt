package com.maodouchat.util

import com.maodouchat.core.model.MessageMeta
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

}
