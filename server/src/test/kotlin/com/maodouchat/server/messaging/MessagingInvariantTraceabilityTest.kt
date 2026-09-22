package com.maodouchat.server.messaging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M3：messaging-v2 不变量的**追溯门禁**。
 *
 * `docs/messaging-v2-architecture.md` 用不变量描述消息管线的契约（G7 时 24 条，G11 增至 25 条，G12 增至 26 条）。在加这个门禁之前，
 * 那份文档**没有任何一处提到测试**：当时全部 24 条都只是散文，读的人无从知道哪条真被验证过、
 * 哪条只是愿望。这次审计把每条都标上了 `→ 验证：`（指向真正执行它的用例）或
 * `→ 缺口：`（明确承认没有用例）。
 *
 * 门禁做三件事：
 * 1. 每条不变量都必须至少有一条 `→ 验证` 或 `→ 缺口`，不允许「忘了标」；
 * 2. 每个 `Class#用例名` 引用都必须**在测试源码里真实存在**——文档写着、测试被删/改名就红；
 * 3. 缺口数按**棘轮**冻结：只许减少。补上一条就把常量改小（这是预期工作流）。
 *
 * 之所以冻结缺口而不是要求清零：这 9 条是真实存在的空白，一次性补齐既不诚实也不现实；
 * 冻结能让「还差多少」始终等于事实，而不是靠人记。
 */
class MessagingInvariantTraceabilityTest {

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "docs/messaging-v2-architecture.md").isFile) return@lazy dir
            dir = dir.parentFile
        }
        fail("找不到仓库根（从 user.dir=${System.getProperty("user.dir")} 向上查找 docs/messaging-v2-architecture.md 失败）")
    }

    private val testSources: List<File> by lazy {
        listOf("server/src/test/kotlin", "app/src/test/java", "app/src/androidTest/java")
            .map { File(repoRoot, it) }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    /**
     * 剥掉行注释与块注释后的源码（G155b）。
     *
     * 门禁原先用「文件全文里有没有这行字符串」来判断引用是否有效，
     * 于是**被注释掉的测试也算存在**——把 `fun \`foo\`` 注释掉，文档引用照样解析成功、
     * 门禁全绿，而 `foo` 其实根本不执行。这与本文件 KDoc 里写的目标
     * （「文档写着而证据早没了」要拦住）直接矛盾。
     */
    private val codeOnlySources: Map<File, String> by lazy {
        testSources.associateWith { stripComments(it.readText()) }
    }

    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var state = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val n = if (i + 1 < text.length) text[i + 1] else ' '
            when (state) {
                0 -> when {
                    c == '/' && n == '/' -> { state = 1; i++ }
                    c == '/' && n == '*' -> { state = 2; i++ }
                    c == '"' -> { state = 3; out.append(c) }
                    c == '\'' -> { state = 4; out.append(c) }
                    else -> out.append(c)
                }
                1 -> if (c == '\n') { state = 0; out.append(c) }
                2 -> if (c == '*' && n == '/') { state = 0; i++ }
                3 -> {
                    out.append(c)
                    if (c == '\\') { out.append(n); i++ } else if (c == '"') state = 0
                }
                4 -> {
                    out.append(c)
                    if (c == '\\') { out.append(n); i++ } else if (c == '\'') state = 0
                }
            }
            i++
        }
        return out.toString()
    }

    /** 一条不变量的审计结果。 */
    private data class Audit(val number: Int, val verified: List<String>, val gaps: List<String>) {
        val pending: Boolean get() = gaps.isNotEmpty()
    }

    private fun audit(): List<Audit> {
        val doc = File(repoRoot, "docs/messaging-v2-architecture.md").readText()
        val section = doc.substringAfter("## Invariants").substringBefore("\n## ")
        val results = mutableListOf<Audit>()
        var current: Triple<Int, MutableList<String>, MutableList<String>>? = null

        fun flush() {
            current?.let { (n, v, g) -> results += Audit(n, v.toList(), g.toList()) }
            current = null
        }

        section.lines().forEach { line ->
            val numbered = Regex("^(\\d+)\\.\\s").find(line)
            when {
                numbered != null -> {
                    flush()
                    current = Triple(numbered.groupValues[1].toInt(), mutableListOf(), mutableListOf())
                }
                line.trimStart().startsWith("→ 验证：") -> {
                    val entry = current ?: return@forEach
                    Regex("`([^`]+#[^`]+)`").findAll(line).forEach { entry.second += it.groupValues[1] }
                }
                line.trimStart().startsWith("→ 缺口：") -> {
                    val entry = current ?: return@forEach
                    entry.third += line.substringAfter("→ 缺口：").trim()
                }
            }
        }
        flush()
        return results
    }

    @Test
    fun `every messaging invariant is either verified by a real test or explicitly declared a gap`() {
        val audits = audit()

        // G11 新增第 25 条（服务端全库不含人类消息明文）；G12 新增第 26 条
        //（收件箱按设备隔离、离线可取、ACK 按设备且授权受限）。冻结值由 24 → 25 → 26 同步。
        assertEquals(26, audits.size, "不变量条数变了：文档改了就必须同步审计，不能悄悄增删")
        assertEquals(
            (1..26).toList(),
            audits.map { it.number },
            "不变量编号必须连续",
        )

        val unannotated = audits.filter { it.verified.isEmpty() && it.gaps.isEmpty() }.map { it.number }
        assertEquals(
            emptyList(),
            unannotated,
            "这些不变量既没有 → 验证 也没有 → 缺口，等于又退回了「散文契约」：$unannotated",
        )
    }

    /**
     * G169b：`server/` 是独立 Gradle 构建，跨构建无法共享 `stripComments` 的实现，
     * 所以只能在本构建内断言「多份拷贝逐字相同」。
     *
     * 该函数已有 4 份（app 侧 2 份 + server 侧 2 份），漂成过 3 个变体。
     * 真正危险的不是美观，而是将来有人只改其中一份——四套门禁会对同一段代码
     * 给出不同判决而不报警。**app 侧与 server 侧之间靠人工同步。**
     */
    @Test
    fun `every copy of stripComments in this build is textually identical`() {
        val copies = testSources
            .filter { it.readText().contains("private fun stripComments(") }
            .map { it.toRelativeString(repoRoot) to extract(it.readText()) }
            .toMap()
        assertTrue(copies.size >= 2, "server 侧应该至少有 2 份 stripComments，实际 ${copies.size} 份")
        val distinct = copies.values.distinct()
        assertEquals(
            1,
            distinct.size,
            "stripComments 的拷贝漂移了——它们必须逐字相同：" +
                copies.entries.joinToString("\n") { "${it.key}: ${if (it.value == distinct.first()) "相同" else "**不同**"}" },
        )
    }

    private fun extract(source: String): String {
        val start = source.indexOf("private fun stripComments(")
        require(start >= 0) { "文件里没有 stripComments" }
        val lineStart = source.lastIndexOf('\n', start) + 1
        val end = source.indexOf("\n    }\n", start)
        require(end >= 0) { "找不到 stripComments 的结束" }
        return source.substring(lineStart, end + 6).trim()
    }

    @Test
    fun `every referenced test actually exists in the test sources`() {
        // 规则刻意**不**假设「文件名 == 类名」：一个 .kt 里可以有多个测试类
        // （MinimalRouteTest.kt 就是如此），按文件名找会误报「找不到测试」。
        //
        // 也刻意同时接受两种函数名写法：JVM 单测用反引号包住带空格的用例名
        // （``fun `a human send leaves no plaintext` ``），而 **instrumented 测试不能**——
        // DEX version < 040 不允许 SimpleName 里有空格，`app/src/androidTest` 只能用
        // camelCase（`fun realX3dhSessionCarries...`）。此前这条规则只认反引号形式，
        // 等于把整个 androidTest 排除在可引用范围之外；而 `app/src/androidTest/java`
        // 明明在扫描列表里——正是这种「扫了却引用不了」的缝，让模拟器门禁静默失效了很久。
        val sources = codeOnlySources
        val missing = mutableListOf<String>()
        audit().flatMap { it.verified }.distinct().forEach { ref ->
            val className = ref.substringBefore('#')
            val testName = ref.substringAfter('#')
            val classDecl = Regex("""\b(class|object)\s+""" + Regex.escape(className) + """\b""")
            val camelCaseDecl = Regex("""fun\s+""" + Regex.escape(testName) + """\s*\(""")
            val owners = sources.filterValues { classDecl.containsMatchIn(it) }
            when {
                owners.isEmpty() -> missing += "$ref（找不到类声明 $className）"
                owners.none { (_, text) ->
                    text.contains("fun `$testName`") || camelCaseDecl.containsMatchIn(text)
                } ->
                    missing += "$ref（$className 里没有这个用例）"
            }
        }
        assertEquals(
            emptyList(),
            missing,
            "文档引用了不存在的测试——文档写着而证据早没了，正是这个门禁要拦的情况：",
        )
    }

    @Test
    fun `the number of declared gaps only goes down`() {
        val pending = audit().filter { it.pending }.map { it.number }
        assertEquals(
            emptyList(),
            pending,
            "缺口集合变了。补上一条就把这里的编号删掉（这是预期工作流）；" +
                "新增缺口则说明有契约退化，必须先补测试。",
        )
    }
}
