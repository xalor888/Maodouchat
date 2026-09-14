package com.maodouchat.server.messaging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M3：messaging-v2 不变量的**追溯门禁**。
 *
 * `docs/messaging-v2-architecture.md` 用 24 条不变量描述消息管线的契约。在加这个门禁之前，
 * 那份文档**没有任何一处提到测试**：全部 24 条都只是散文，读的人无从知道哪条真被验证过、
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

        assertEquals(24, audits.size, "不变量条数变了：文档改了就必须同步审计，不能悄悄增删")
        assertEquals(
            (1..24).toList(),
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

    @Test
    fun `every referenced test actually exists in the test sources`() {
        val missing = mutableListOf<String>()
        audit().flatMap { it.verified }.distinct().forEach { ref ->
            val className = ref.substringBefore('#')
            val testName = ref.substringAfter('#')
            val owner = testSources.filter { it.name == "$className.kt" }
            when {
                owner.isEmpty() -> missing += "$ref（找不到 $className.kt）"
                owner.none { it.readText().contains("fun `$testName`") } ->
                    missing += "$ref（$className.kt 里没有这个用例）"
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
            listOf(2, 3, 5, 6, 7, 17, 21, 24),
            pending,
            "缺口集合变了。补上一条就把这里的编号删掉（这是预期工作流）；" +
                "新增缺口则说明有契约退化，必须先补测试。",
        )
    }
}
