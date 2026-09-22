package com.maodouchat.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertTrue

/**
 * 群玩法消息 wire 格式 round-trip 测试：parse(format(x)) 必须还原原始语义。
 * round-trip 断裂 = 群玩法互操作 bug（对端无法解析展示），故全量覆盖。
 */
class GroupPlayPolicyTest {

    @Test
    fun `dice round trips value and sides`() {
        val content = GroupPlayPolicy.formatDiceMessage(value = 4, sides = 20, rollerName = "Alice")
        val parsed = GroupPlayPolicy.parseDice(content)
        assertEquals(20 to 4, parsed)
    }

    @Test
    fun `dice parse rejects non dice content`() {
        assertNull(GroupPlayPolicy.parseDice("just text"))
        assertNull(GroupPlayPolicy.parseDice(""))
    }

    @Test
    fun `rollDice stays within bounds`() {
        repeat(50) {
            val v = GroupPlayPolicy.rollDice(sides = 6)
            assertTrue(v in 1..6)
        }
        // 边界钳制：过小/过大面数被约束到 2..100
        repeat(20) {
            assertTrue(GroupPlayPolicy.rollDice(sides = 1) in 1..2)
            assertTrue(GroupPlayPolicy.rollDice(sides = 10_000) in 1..100)
        }
    }

    @Test
    fun `number bomb round trips max secret and label`() {
        val content = GroupPlayPolicy.formatNumberBomb(secret = 42, max = 100, hostLabel = "Host")
        val parsed = GroupPlayPolicy.parseNumberBomb(content)
        assertEquals(Triple(100, 42, "Host started number bomb (1-100)"), parsed)
    }

    @Test
    fun `lucky draw round trips picker and target`() {
        val content = GroupPlayPolicy.formatLuckyDraw("Host", "Bob")
        val parsed = GroupPlayPolicy.parseLuckyDraw(content)
        assertNotNull(parsed)
        assertTrue(parsed.first.contains("Host") || parsed.second.contains("Bob"))
    }

    @Test
    fun `rps round trips choice`() {
        listOf("rock", "paper", "scissors").forEach { choice ->
            val parsed = GroupPlayPolicy.parseRps(GroupPlayPolicy.formatRps(choice, "Alice"))
            assertEquals(choice, parsed)
        }
    }

    @Test
    fun `reaction race round trips token`() {
        val token = GroupPlayPolicy.randomRaceToken()
        val parsed = GroupPlayPolicy.parseReactionRace(GroupPlayPolicy.formatReactionRace(token, "Host"))
        assertNotNull(parsed)
        assertEquals(token, parsed?.first)
    }

    @Test
    fun `would you rather round trips both options`() {
        val parsed = GroupPlayPolicy.parseWouldYouRather(
            GroupPlayPolicy.formatWouldYouRather("coffee", "tea", "Host")
        )
        assertNotNull(parsed)
        assertEquals("coffee", parsed?.first)
        assertEquals("tea", parsed?.second)
    }

    @Test
    fun `emoji rain round trips emoji`() {
        val parsed = GroupPlayPolicy.parseEmojiRain(GroupPlayPolicy.formatEmojiRain("Host", "🎉"))
        assertNotNull(parsed)
        assertEquals("🎉", parsed?.first)
    }

    @Test
    fun `two truths one lie round trips all three statements`() {
        val parsed = GroupPlayPolicy.parseTwoTruthsOneLie(
            GroupPlayPolicy.formatTwoTruthsOneLie("t1", "t2", "lie", "Host")
        )
        assertEquals(listOf("t1", "t2", "lie"), parsed)
    }

    @Test
    fun `quiz round trips question answer and options`() {
        val parsed = GroupPlayPolicy.parseQuiz(
            GroupPlayPolicy.formatQuiz("Q?", "A", listOf("A", "B", "C"), "Host")
        )
        assertNotNull(parsed)
        assertEquals("Q?", parsed?.first)
        assertEquals("A", parsed?.second)
        assertEquals(listOf("A", "B", "C"), parsed?.third)
    }

    @Test
    fun `quiz options with separator chars survive escaping`() {
        // 选项含分隔符 ^ 与 | 时，esc/unesc 必须保证 round-trip 不断裂
        val tricky = listOf("opt^one", "opt|two", "plain")
        val parsed = GroupPlayPolicy.parseQuiz(
            GroupPlayPolicy.formatQuiz("Q?", "A", tricky, "Host")
        )
        assertEquals(tricky, parsed?.third)
    }

    @Test
    fun `spin story countdown round trip`() {
        assertEquals("result", GroupPlayPolicy.parseSpin(GroupPlayPolicy.formatSpin("result", "Host")))
        assertEquals("seed", GroupPlayPolicy.parseStory(GroupPlayPolicy.formatStory("seed", "Host")))
        assertEquals(30, GroupPlayPolicy.parseCountdown(GroupPlayPolicy.formatCountdown(30, "Host")))
    }

    @Test
    fun `parse helpers reject foreign prefixes`() {
        assertNull(GroupPlayPolicy.parseSpin("STORY:x|y"))
        assertNull(GroupPlayPolicy.parseStory("SPIN:x|y"))
        assertNull(GroupPlayPolicy.parseCountdown("not a countdown"))
    }

    @Test
    fun `bingo round trips board cells`() {
        val board = listOf("🍎", "🚀", "🎲", "🌟", "🍀", "🎯")
        val parsed = GroupPlayPolicy.parseBingo(GroupPlayPolicy.formatBingo(board, "Host"))
        assertEquals(board, parsed)
    }

    @Test
    fun `lottery round trips winner and pool with separator chars`() {
        // 奖池是用户输入，含 ^/| 时 round-trip 不得断裂（9.224 修复点）
        val pool = listOf("alice^1", "bob|2", "carol")
        val parsed = GroupPlayPolicy.parseLottery(GroupPlayPolicy.formatLottery(pool, "alice^1", "Host"))
        assertEquals("alice^1", parsed?.first)
        assertEquals(pool, parsed?.second)
    }

    @Test
    fun `hot seat round trips target`() {
        assertEquals("Bob", GroupPlayPolicy.parseHotSeat(GroupPlayPolicy.formatHotSeat("Bob", "Host")))
        // 空白目标回退为 someone
        assertEquals("someone", GroupPlayPolicy.parseHotSeat(GroupPlayPolicy.formatHotSeat("   ", "Host")))
    }

    /**
     * G167b：**零引用成员的棘轮**。
     *
     * G166b 实测：`GroupPlayPolicy` 有 542 个成员函数，其中 **297 个在全仓库
     * （1580 个 .kt，含 app/server/core/domain/feature 与测试）一个引用都没有**——
     * 每个名字只出现在自己的定义行上。已抽样核对过若干成员（转盘/抛硬币/宾果类），也确认了没有反射分发。
     *
     * 为什么冻结而不删：这些看起来像**内容路线图**（转盘、宾果、抛硬币、记忆配对……），
     * 删不删是产品决策，不是我能单方面替用户做的。但「有 297 个死成员」这个事实
     * 必须有个东西盯着，否则它只会在某次闲聊里被提到、然后被忘掉。
     *
     * 语义：
     * - 真去删死代码 → 数字下降 → 把 [UNREFERENCED_BASELINE] 改小（**这是鼓励的工作流**）；
     * - 新增一个没人调用的成员 → 数字上升 → 红（又往路线图里加东西，至少先睁眼看一眼）。
     */
    @Test
    fun `unreferenced members only shrink`() {
        val source = File(TEST_SOURCE_ROOT, "app/src/main/java/com/maodouchat/util/GroupPlayPolicy.kt")
        assertTrue(source.isFile, "GroupPlayPolicy.kt 不存在：${source.path}")
        val policyText = stripComments(source.readText())
        val members = Regex("""^    (?:internal |private )?(?:suspend )?fun (\w+)\(""", RegexOption.MULTILINE)
            .findAll(policyText).map { it.groupValues[1] }.toList()
        assertTrue(members.size > 400, "成员数异常少（${members.size}）——扫描口径可能坏了")

        // DIRECTION.md 3.5：源码文本判决第一步必须剥注释。
        // 第一版**没剥**，结果本用例自己的 KDoc 里举例提到的 `spinWheel` 被当成了
        // 「有人引用它」——死成员数因此少了 1，而且这个数还会随我改注释而变。
        val allSources = sequenceOf("app/src", "server/src", "core", "domain", "feature")
            .map { File(TEST_SOURCE_ROOT, it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.asSequence() }
            .map { stripComments(it.readText()) }
            .toList()

        val unreferenced = members.filter { name ->
            // 全仓库出现次数减去「本文件里的定义行」——只剩定义行即零引用
            val total = allSources.sumOf { Regex("""\b${Regex.escape(name)}\b""").findAll(it).count() }
            val definitions = Regex("""^    (?:internal |private )?(?:suspend )?fun ${Regex.escape(name)}\(""",
                RegexOption.MULTILINE).findAll(policyText).count()
            total - definitions == 0
        }
        assertEquals(
            UNREFERENCED_BASELINE,
            unreferenced.size,
            "GroupPlayPolicy 的零引用成员数变了。变少是好事（真删了死代码）——" +
                "把 UNREFERENCED_BASELINE 改小；变多说明又加了没人调用的成员，先确认是否有意。\n" +
                "新增的零引用成员：${unreferenced.map { it }}",
        )
    }

    /** G216b：零引用的 `val`/`var` 声明（口径与 fun 那条完全一致）。 */
    private fun unreferencedVals(policyText: String, allSources: List<String>): List<String> {
        val vals = Regex("""^    (?:internal |private )?(?:const )?(?:val|var) (\w+)""", RegexOption.MULTILINE)
            .findAll(policyText).map { it.groupValues[1] }.toList()
        return vals.filter { name ->
            val total = allSources.sumOf { Regex("""${Regex.escape(name)}""").findAll(it).count() }
            val definitions = Regex("""^    (?:internal |private )?(?:const )?(?:val|var) ${Regex.escape(name)}""",
                RegexOption.MULTILINE).findAll(policyText).count()
            total - definitions == 0
        }
    }

    /**
     * G216b：零引用 val/var 棘轮。
     *
     * 基线先填 0，**让门禁自己报出真实数**（G167b 的教训：基线不能手推）。
     * 首次运行会红并打印实际零引用集合，把那个数填进常量即完成冻结。
     */
    @Test
    fun `unreferenced vals only shrink`() {
        val source = File(TEST_SOURCE_ROOT, "app/src/main/java/com/maodouchat/util/GroupPlayPolicy.kt")
        assertTrue(source.isFile, "GroupPlayPolicy.kt 不存在：${source.path}")
        val policyText = stripComments(source.readText())
        val allSources = sequenceOf("app/src", "server/src", "core", "domain", "feature")
            .map { File(TEST_SOURCE_ROOT, it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.asSequence() }
            .map { stripComments(it.readText()) }
            .toList()

        val vals = Regex("""^    (?:internal |private )?(?:const )?(?:val|var) (\w+)""", RegexOption.MULTILINE)
            .findAll(policyText).map { it.groupValues[1] }.toList()
        assertTrue(vals.isNotEmpty(), "没扫到任何 val/var——扫描口径可能坏了")

        val dead = unreferencedVals(policyText, allSources)
        assertEquals(
            UNREFERENCED_VAL_BASELINE,
            dead.size,
            "GroupPlayPolicy 的零引用 val/var 数变了。这是新监管面（原先只扫 fun），" +
                "首次冻结请把实际数填进 UNREFERENCED_VAL_BASELINE。\n零引用：${dead.sorted()}"
        )
    }

    /** 剥掉行注释与块注释后的源码（字符串字面量里的双斜线 与 斜线星 不误剥）。 */
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

    private companion object {
        /** G167b 冻结值：542 个成员里 297 个零引用。 */
        const val UNREFERENCED_BASELINE = 297

    /**
     * G216b：`val`/`var` 声明的零引用冻结值。
     *
     * 原棘轮只扫 `fun`——GroupPlayPolicy 里另有 173 个 val/var 声明，
     * 它们完全不在监管范围内：新增一个没人用的常量不会让任何门禁变红。
     * 这是同一类「门禁只覆盖了一半」的缝。
     */
    const val UNREFERENCED_VAL_BASELINE = 173

        /** 仓库根（测试的 user.dir 可能是模块目录，向上找到 settings.gradle.kts + app）。 */
        val TEST_SOURCE_ROOT: File = run {
            var dir: File? = File("").absoluteFile
            while (dir != null && !(File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory)) {
                dir = dir.parentFile
            }
            checkNotNull(dir) { "找不到仓库根（从 ${File("").absoluteFile} 向上）" }
        }
    }
}
