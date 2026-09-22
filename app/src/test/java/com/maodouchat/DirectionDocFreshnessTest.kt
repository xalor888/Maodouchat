package com.maodouchat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G172b：`DIRECTION.md` §0「我实测到的现状」的**新鲜度门禁**。
 *
 * G171b 把 §0 的每个数字都配上了可复现命令，但那仍要人**记得去跑**——
 * 文档腐烂依旧会安静地误导每个读它的人（G171b 自己记下的教训原话）。
 *
 * 按 DIRECTION.md 自己的判据：「任何一条架构断言，要么有会失败的测试，
 * 要么不许写进文档当已完成。」§0 那张表正是一堆架构断言，所以它该有测试。
 *
 * 做法：解析 §0 表格的每一行，用**当初实测的同一条命令**重算，逐条断言相等。
 * 表格增删行时必须同步改这里（`the direction table has exactly the rows we verify` 那条
 * 会拦住「悄悄少检几行」）。
 */
class DirectionDocFreshnessTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory)) {
            dir = dir.parentFile
        }
        checkNotNull(dir) { "找不到仓库根（从 ${File("").absoluteFile} 向上）" }
    }

    private val direction: File get() = File(repoRoot, "DIRECTION.md")

    private fun section0(): String {
        val text = direction.readText()
        val start = text.indexOf("## 0. 我实测到的现状")
        assertTrue("DIRECTION.md 里找不到 §0") { start >= 0 }
        val end = text.indexOf("\n## ", start + 5)
        return text.substring(start, if (end > 0) end else text.length)
    }

    /** §0 表格的数据行（`| x | y | z |`，跳过表头与分隔行）。 */
    private fun dataRows(): List<Triple<String, String, String>> =
        section0().lines()
            .filter { it.startsWith("|") && !it.startsWith("|-") }
            .drop(1) // 表头
            .map { line ->
                val cells = line.trim().trim('|').split('|').map { it.trim() }
                Triple(cells.getOrElse(0) { "" }, cells.getOrElse(1) { "" }, cells.getOrElse(2) { "" })
            }

    private fun countFiles(dir: String): Int {
        val root = File(repoRoot, dir)
        return root.walkTopDown().count { it.isFile && it.extension == "kt" }
    }

    private fun shell(command: String): Int {
        val pb = ProcessBuilder("sh", "-c", command).directory(repoRoot).redirectErrorStream(true)
        val out = pb.start().inputStream.bufferedReader().readText().trim()
        return out.toIntOrNull() ?: error("命令 [$command] 的输出不是数字：$out")
    }

    @Test
    fun `the direction table has exactly the rows we verify`() {
        // 10 行数据。增删行必须同步改本测试——否则「悄悄少检几行」没人发现。
        assertEquals(10, dataRows().size, "§0 表格行数变了：${dataRows().map { it.first }}")
    }

    @Test
    fun `tracked file count matches the table`() {
        val row = dataRows().first { it.first == "已跟踪文件" }
        assertEquals(shell("git ls-files | wc -l"), row.second.trim().toInt(), "已跟踪文件")
    }

    @Test
    fun `server test file count matches the table`() {
        val row = dataRows().first { it.first.startsWith("服务端测试文件") }
        // 单元格形如「117 个 / 462 绿」，取第一个数字
        val declared = Regex("(\\d+)").find(row.second)!!.groupValues[1].toInt()
        assertEquals(countFiles("server/src/test"), declared, "服务端测试文件数")
    }

    @Test
    fun `client test file count matches the table`() {
        val row = dataRows().first { it.first.startsWith("客户端 JVM 测试文件") }
        val declared = Regex("(\\d+)").find(row.second)!!.groupValues[1].toInt()
        assertEquals(countFiles("app/src/test"), declared, "客户端 JVM 测试文件数")
    }

    @Test
    fun `instrumented test file count matches the table`() {
        val row = dataRows().first { it.first.startsWith("instrumented") }
        val declared = Regex("(\\d+)").find(row.second)!!.groupValues[1].toInt()
        assertEquals(countFiles("app/src/androidTest"), declared, "instrumented 文件数")
    }

    @Test
    fun `checklist byte size matches the table`() {
        val row = dataRows().first { it.first == "自审清单体量" }
        val declared = Regex("[\\d,]+").find(row.second)!!.value.replace(",", "").toLong()
        val actual = File(repoRoot, "docs/full-project-refactor-checklist.md").length()
        assertEquals(declared, actual, "自审清单字节数——每轮都会变，别忘同步")
    }

    @Test
    fun `plugins transaction blocks match the table`() {
        val row = dataRows().first { it.first.contains("transaction") }
        val declared = Regex("(\\d+)").find(row.second)!!.groupValues[1].toInt()
        assertEquals(
            shell("grep -rho 'transaction {' server/src/main/kotlin/com/maodouchat/server/plugins/ | wc -l"),
            declared,
            "plugins/ 内 transaction { 数",
        )
    }

    @Test
    fun `worst single file line count matches the table`() {
        val row = dataRows().first { it.first == "最差单文件" }
        val declared = Regex("(\\d+)").find(row.second)!!.groupValues[1].toInt()
        val worst = File(repoRoot, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .maxOf { it.readLines().size }
        assertEquals(worst, declared, "最差单文件行数")
    }

    @Test
    fun `plugins files importing exposed match the table`() {
        val row = dataRows().first { it.first.contains("import Exposed") }
        val declared = Regex("(\\d+)").find(row.second)!!.groupValues[1].toInt()
        assertEquals(
            shell("grep -rl org.jetbrains.exposed server/src/main/kotlin/com/maodouchat/server/plugins/ | wc -l"),
            declared,
            "plugins/ 中 import Exposed 的文件数",
        )
    }

    @Test
    fun `repository to plugins back dependency matches the table`() {
        val row = dataRows().first { it.first.contains("反向依赖") }
        val declared = Regex("(\\d+)").find(row.second)!!.groupValues[1].toInt()
        assertEquals(
            shell("grep -rn 'com.maodouchat.server.plugins' server/src/main/kotlin/com/maodouchat/server/repository/ | grep -c import"),
            declared,
            "repository/ → plugins/ 反向依赖",
        )
    }

    @Test
    fun `repository service file count matches the table`() {
        val row = dataRows().first { it.first.contains("*Service.kt") }
        val declared = Regex("(\\d+)").find(row.second)!!.groupValues[1].toInt()
        val dir = File(repoRoot, "server/src/main/kotlin/com/maodouchat/server/repository")
        assertEquals(dir.list()!!.count { it.endsWith("Service.kt") }, declared, "repository 下 *Service.kt")
    }
}
