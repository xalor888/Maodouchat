package com.maodouchat.util

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G195b：客户端与服务端两份 `DisappearingMessagePolicy` 的**一致性闸门**。
 *
 * 背景：这个策略有**两份独立实现**——
 * - `app/src/main/java/com/maodouchat/util/DisappearingMessagePolicy.kt`
 * - `server/src/main/kotlin/com/maodouchat/server/service/DisappearingMessagePolicy.kt`
 *
 * 客户端 KDoc 写着「服务端与客户端共用同一套合法时长」。但 `server/` 是**独立的
 * Gradle 构建**，两份代码不在同一编译单元里，所谓「共用」实际靠**人工复制粘贴**维持。
 * 没有任何东西阻止它们漂移。
 *
 * 漂移的后果很实在：客户端允许某个时长、服务端不认 → 消息在服务端永不过期
 * （或反过来，客户端拒绝服务端已接受的时长，用户看到的是「设了却没生效」）。
 * 在阅后即焚这个语境里，这是**消息不消失**——安全性质。
 *
 * 这里的判据只基于**代码文本**，所以按源码文本门禁约定**先剥注释再比**——
 * 否则服务端少一行 KDoc、或多一个 `remainingMs`（UI 专用），都会误报。
 */
class DisappearingMessagePolicyParityTest {

    private fun client(): File =
        File("src/main/java/com/maodouchat/util/DisappearingMessagePolicy.kt")

    private fun server(): File =
        File("../server/src/main/kotlin/com/maodouchat/server/service/DisappearingMessagePolicy.kt")

    /** 与各门禁同一份实现：剥掉块注释与行注释（保留字符串内的 #）。 */
    private fun codeOnly(raw: String): String {
        val out = StringBuilder()
        var i = 0
        var lineStart = true
        while (i < raw.length) {
            val c = raw[i]
            if (lineStart && raw.startsWith("//", i)) {
                while (i < raw.length && raw[i] != '\n') i++
                continue
            }
            if (raw.startsWith("/*", i)) {
                val end = raw.indexOf("*/", i + 2)
                i = if (end < 0) raw.length else end + 2
                continue
            }
            if (c == '"') {
                val end = raw.indexOf('"', i + 1)
                out.append(raw, i, if (end < 0) raw.length else end + 1)
                i = if (end < 0) raw.length else end + 1
                lineStart = false
                continue
            }
            out.append(if (c == '\n') '\n' else c)
            lineStart = (c == '\n')
            i++
        }
        return out.toString()
    }

    /**
     * 取出 `fun xxx(...)` 的签名 + 体，压成一行。
     *
     * 必须同时支持**两种函数体**，否则配平会跑飞：
     * - 块体 `fun f(...): T { ... }` —— 配平到对应的 `}`；
     * - 单表达式体 `fun f(...): T = expr`（**没有花括号**）—— 取到行尾。
     *   第一版只处理块体，遇到单表达式体时 depth 永远回不到 0，
     *   于是一路吞到下一个函数的 `}` 才停，把好几个函数搅成一条。
     */
    private fun funBody(code: String, name: String): String {
        val m = Regex("fun $name\\s*\\(").find(code) ?: return "<缺失 fun $name>"
        var i = m.range.last + 1
        var depth = 1
        while (i < code.length && depth > 0) { // 先配平形参括号
            when (code[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        val rest = code.substring(i).trimStart()
        val ws = code.substring(i).length - rest.length
        val bodyStart = i + ws
        val extracted = if (rest.startsWith("{")) {
            var j = bodyStart
            var d = 1
            j++
            while (j < code.length && d > 0) {
                when (code[j]) {
                    '{' -> d++
                    '}' -> d--
                }
                j++
            }
            code.substring(m.range.first, j)
        } else {
            val lineEnd = code.indexOf('\n', bodyStart)
            code.substring(m.range.first, if (lineEnd < 0) code.length else lineEnd)
        }
        return extracted.replace(Regex("\\s+"), " ").trim()
    }

    private val constNames = listOf("OFF_SECONDS", "SECRET_DEFAULT_SECONDS")

    /** 参与对账的函数：两份实现都必须有、且语义相同。 */
    private val sharedFuns = listOf(
        "isAllowedSeconds",
        "normalizeSeconds",
        "effectiveSeconds",
        "resolveExpiresAt",
        "shouldArmOnVisible",
        "isExpired",
    )

    private fun normalized(code: String, name: String): String {
        val body = funBody(code, name)
        // 只保留「形参名 + 类型」与「表达式」，抹平换行/默认值书写差异
        return body
            .replace(Regex("\\s+"), " ")
            // 标点两侧的空白也要抹掉：客户端把形参写成多行、
            // 服务端写成一行，压成一行后会差在 `( ` vs `(`。
            .replace(Regex("\\s*([(),{}])\\s*"), "$1")
            .replace(":Boolean=true", "")
            .replace(":Int=", ":")
            .trim()
    }

    @Test
    fun clientAndServerAgreeOnEveryConstant() {
        val c = codeOnly(client().readText())
        val s = codeOnly(server().readText())
        constNames.forEach { name ->
            val cv = Regex("const val $name\\s*=\\s*([^\\n]+)").find(c)?.groupValues?.get(1)?.trim()
            val sv = Regex("const val $name\\s*=\\s*([^\\n]+)").find(s)?.groupValues?.get(1)?.trim()
            assertEquals(cv, sv, "常量 $name 必须两份一致（当前客户端=$cv 服务端=$sv）")
        }
    }

    @Test
    fun clientAndServerAgreeOnTheAllowedDurations() {
        val c = codeOnly(client().readText())
        val s = codeOnly(server().readText())
        val pick: (String) -> List<String> = { code ->
            Regex("val ALLOWED_SECONDS[^=]*=\\s*(?:listOf|setOf)\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
                .find(code)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
        }
        val cv = pick(c)
        val sv = pick(s)
        assertTrue(cv.isNotEmpty(), "客户端 ALLOWED_SECONDS 解析为空，闸门失效")
        assertEquals(cv, sv, "两份实现的合法时长集合必须一致")
    }

    @Test
    fun clientAndServerAgreeOnEverySharedFunction() {
        val c = codeOnly(client().readText())
        val s = codeOnly(server().readText())
        sharedFuns.forEach { name ->
            assertEquals(
                normalized(c, name), normalized(s, name),
                "函数 $name 在客户端与服务端不一致——阅后即焚两份实现已漂移",
            )
        }
    }

    @Test
    fun neitherSideDropsASharedFunction() {
        val c = codeOnly(client().readText())
        val s = codeOnly(server().readText())
        sharedFuns.forEach { name ->
            assertTrue(c.contains("fun $name"), "客户端缺少 fun $name")
            assertTrue(s.contains("fun $name"), "服务端缺少 fun $name")
        }
    }
}
