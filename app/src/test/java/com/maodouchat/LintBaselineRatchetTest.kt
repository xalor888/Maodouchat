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
    private val frozenIssueCount = 73

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
            // 2026-09-27：297 条未被任何代码/脚本/其它 XML 引用的死 string 已删除
            // （逐条全仓核验；唯一被挡下的 `secret_chat_enabled` 与特性开关键同名，保留）。
            "UnusedResources" to 1,
            // 2026-09-27：UseKtx 的 `String.toUri` 一族收口——25 个文件 47 处
            // `Uri.parse(x)` → `x.toUri()`，同步删掉 44 条对应条目 + 3 条死条目
            // （AppLinkOpener 迁包后的旧路径 ×2、ChatDetailRoute 抽层后失效 ×1）；
            // 另有 5 条 GradleDependency 死条目（可用版本号已变，lint 报为 unmatched）一并删除。
            // 209 → 162，总条目 318 → 265。
            //
            // 2026-09-28：`SharedPreferences.edit` 一族收口——55 个文件 137 处
            // `.edit().a().b().apply()` → `.edit { a(); b() }`（commit 链 → `edit(commit = true)`；
            // 137 = 135 处对应基线块 + 2 处 lint 原先未标、邻位转换后转活跃的站点，被活跃守卫当场抓住后一并转），
            // 按基线 153 条逐条匹配（errorLine1 + 行号就近消歧），保留 18 处不转
            // （11 处变量编辑器模式 + `check(...commit())` 1 处 + runCatching 返回值被消费的 commit 链 4 处
            // + GlobalSearchScreen 2 处——零松量行数上限文件，转换要 +1 import 行，先拆后收）。
            // 162 → 27（= 5 bitmap + 4 处行数上限热点文件内的 Uri.parse + 18 处本批保留），总条目 265 → 130。
            //
            // 2026-09-28：bitmap 小族收口——4 文件 5 处（Bitmap.createBitmap ×3 → ktx `createBitmap`、
            // createScaledBitmap → `Bitmap.scale`、setPixel → `Bitmap.set` 下标赋值）。
            // 27 → 22，总条目 130 → 125。
            "UseKtx" to 22,
            // 上面两条是 warning 级的大头；真正卡 CI 的是它：
            "LocalContextGetResourceValueCall" to 0,
            "GradleDependency" to 17,
            "NewerVersionAvailable" to 14,
            // 2026-09-28：HardwareIds 一族收口——8 处 ANDROID_ID 读取集中到
            // watermark/DeviceHint（一处豁免）+ SimChangeWatcher 的 SIM 标识读取带理由豁免。
            "HardwareIds" to 0,
        )
        expected.forEach { (rule, n) ->
            assertEquals(n, byRule[rule] ?: 0, "基线里 $rule 的条数应为 $n")
        }
    }
}
