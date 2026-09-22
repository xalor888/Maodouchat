package com.maodouchat

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G223b：**lint 基线只许缩、不许长**。
 *
 * 背景：CI 的 `:app:lintDebug` 在加基线之前是**红的**——42 个 Error
 * （41 个 `LocalContextGetResourceValueCall` + 1 个 `SuspiciousIndentation`）。
 * 这些大多是 Compose lint 版本升级带来的**新规则**撞上老代码，不是本项目新写的违规。
 *
 * 用基线让 CI 绿是标准做法，但基线是个**会腐烂**的文件：
 * 如果它只进不出，「遗留」就会变成「永久豁免」，而且新违规也会被无声地并进去。
 * 这条测试把语义补成：
 *
 * - 每条 issue 被修掉 → 必须同步从基线删掉，否则这里红（**变少要同步**）；
 * - 总条数不许变多（**变多必红**）。
 *
 * 这样基线就是一个只能下降的棘轮，和项目里其它所有棘轮同一套语义。
 */
class LintBaselineRatchetTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "找不到仓库根" }
    }

    private fun baseline(): File = File(repoRoot(), "app/lint-baseline.xml")

    /** 基线里的 issue 元素（`<issue id=... >`），按出现顺序。 */
    /**
     * 基线里的 issue 元素，按出现顺序。
     *
     * 故意用普通字符串 + 转义，不用 Kotlin raw string：
     * `"""...id="([^"]+)""""` 的收尾引号有歧义，第一版就那么写的，
     * 结果正则一条都没匹配上，而「只许缩」用例因为 0 <= 659 **恒真通过了**
     * ——又一条空转的门禁。
     */
    private fun issues(): List<String> =
        Regex("<issue\\s+id=\"([^\"]+)\"").findAll(baseline().readText())
            .map { it.groupValues[1] }.toList()

    /**
     * 冻结值：加基线那一刻的 issue 条数（G223b，`./gradlew :app:updateLintBaseline` 自报）。
     * 修掉任何一条，就把这个数往下调——**这是被鼓励的方向**。
     */
    private val frozenIssueCount = 657

    @Test
    fun `lint baseline can only shrink`() {
        assertTrue(baseline().isFile, "app/lint-baseline.xml 不见了——lint 会重新开始报全部 659 条")
        val actual = issues()
        assertTrue(actual.isNotEmpty(), "一条 issue 都没解析到——正则坏了，这条门禁正在空转")
        assertTrue(
            actual.size <= frozenIssueCount,
            "lint 基线从 $frozenIssueCount 条长到 ${actual.size} 条。" +
                "只应该是「修掉一条、删掉一条」；变多说明有人把新违规并进了豁免。",
        )
    }

    @Test
    fun `lint baseline issue mix is what we agreed to`() {
        // 按规则分布也冻一遍：光看总数不够——把 41 条 `LocalContextGetResourceValueCall`
        // 换成 41 条别的新违规，总数不变但性质变了。
        val byRule = issues().groupingBy { it }.eachCount()
        val expected = mapOf(
            "UnusedResources" to 298,
            "UseKtx" to 209,
            // 上面两条是 warning 级的大头；真正卡 CI 的是它：
            "LocalContextGetResourceValueCall" to 40,
            "GradleDependency" to 22,
            "NewerVersionAvailable" to 14,
            "HardwareIds" to 10,
        )
        expected.forEach { (rule, n) ->
            assertEquals(n, byRule[rule] ?: 0, "基线里 $rule 的条数应为 $n")
        }
    }
}
