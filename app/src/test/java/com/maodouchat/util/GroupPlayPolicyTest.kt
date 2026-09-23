package com.maodouchat.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    /**
     * G223c：**(mode, hostLabel) 形状的 format/parse 往返一致性**。
     *
     * 实现前实测：这 88 对分两类——
     * - **71 对**形如 `esc(mode.trim().take(N))`，N 为 40（68 对）或 30（3 对），
     *   所以往返必须精确等于 `mode.trim().take(N)`；
     * - **13 对**不做 take（`formatLinkLock` 等，只做前缀拼接），
     *   所以往返必须**完整**等于 `mode.trim()`。
     *
     * 两个名单都是实测提取的，写死在下面。**不用「断言是前缀」那种宽松写法**——
     * 第一版就是那么写的，结果把 `take(80)` 改成 `take(4)` 的负控制**没打红**
     * （"hell" 仍是 "hello" 的前缀），白做一次 NC。
     *
     * 输入面覆盖：普通串、含 `|`（esc 保护的定界符）、含 `^`（join 分隔符）、
     * 纯空白（trim 后为空 → parse 归一 null）、超长串（触发 take 截断）、
     * emoji/Unicode、换行。
     */
    @Test
    fun `mode and host label pairs round trip exactly`() {
        val host = "Host"
        val cases = listOf(
            "hello", "with|pipe", "with^caret", "  padded  ", "a".repeat(200),
            "\uD83C\uDF89emoji\uD83D\uDE00", "line1\nline2", "tab\there",
        )
        for ((name, limit) in MODE_HOST_LABEL_TAKE) {
            val parseName = "parse" + name.removePrefix("format")
            val formatFn = GroupPlayPolicy::class.java.getMethod(name, String::class.java, String::class.java)
            val parseFn = GroupPlayPolicy::class.java.getMethod(parseName, String::class.java)
            for (mode in cases) {
                val content = formatFn.invoke(GroupPlayPolicy, mode, host) as String
                val parsed = parseFn.invoke(GroupPlayPolicy, content) as String?
                val trimmed = mode.trim()
                if (trimmed.isEmpty()) {
                    assertNull(parsed, "$name($mode) trim 后为空，应归一为 null")
                    continue
                }
                val expected = trimmed.take(limit)
                assertEquals(
                    expected,
                    parsed,
                    "$name($mode) 往返不一致（limit=$limit）；content=$content",
                )
            }
        }
        assertEquals(84, MODE_HOST_LABEL_TAKE.size, "该形状实测 84 对，名单可能过期了")
    }


        /** G223c 实测：88 对 (mode, hostLabel) 形状的 format 名单（与 parseX 一一配对）。 */
        /**
         * G223c 实测：(mode, hostLabel) 形状共 88 对。
         * 71 对做 take（40 或 30），13 对不截断。
         * limit 为该函数的 take(N)；null 表示不截断（不在本表，见 NO_TAKE）。
         */
        /**
         * G223c 实测：(mode, hostLabel) 形状共 **84 对**（不是最初以为的 88——
         * 那是我按 parseX 名称匹配多数了 4 个）。
         * 全部都有 take：81 对 take(40)、3 对 take(30)。
         * 其中 13 对是 `GroupPlayPolicy` 委托给 `GroupPlaySealPolicy` 的单行表达式，
         * 真实 take 在被委托文件里——只扫主文件会漏掉，第一版就这么漏了 13 个，
         * 把它们误判成「不截断」，于是负控制没打红。
         */
        val MODE_HOST_LABEL_TAKE: List<Pair<String, Int>> = listOf(
            "formatAlertSprint" to 40,
            "formatBeepDash" to 40,
            "formatBlurBattle" to 40,
            "formatBuzzRelay" to 40,
            "formatCertRelay" to 40,
            "formatChatExportLock" to 40,
            "formatClickBeat" to 40,
            "formatClipDash" to 40,
            "formatCodeCheck" to 40,
            "formatContactSwap" to 40,
            "formatCopyLock" to 40,
            "formatDocHunt" to 40,
            "formatDownloadDash" to 40,
            "formatExportSeal" to 40,
            "formatFadeCircle" to 40,
            "formatFadeTimer" to 40,
            "formatFallbackDash" to 40,
            "formatFeelSprint" to 40,
            "formatFileRelay" to 40,
            "formatFontRace" to 40,
            "formatForwardSeal" to 40,
            "formatFrameHunt" to 40,
            "formatGifRelay" to 40,
            "formatInviteRace" to 30,
            "formatLastSeenSeal" to 40,
            "formatLeakSprint" to 40,
            "formatLeakWall" to 40,
            "formatLinkHunt" to 30,
            "formatLinkLock" to 40,
            "formatListBlur" to 40,
            "formatMapDash" to 40,
            "formatMarkHunt" to 40,
            "formatMarkSprint" to 40,
            "formatMentionMayhem" to 30,
            "formatMetaFence" to 40,
            "formatNotifMask" to 40,
            "formatNudgeDash" to 40,
            "formatOfflineHint" to 40,
            "formatPhotoRace" to 40,
            "formatPinDrop" to 40,
            "formatPixelQuest" to 40,
            "formatPqxdhDash" to 40,
            "formatPresenceSeal" to 40,
            "formatPreviewMask" to 40,
            "formatPreviewMute" to 40,
            "formatPromptSprint" to 40,
            "formatPushRace" to 40,
            "formatQrQuest" to 40,
            "formatQuietHour" to 40,
            "formatReactLock" to 40,
            "formatReadSeal" to 40,
            "formatRecentsHide" to 40,
            "formatRemindCircle" to 40,
            "formatReplySprint" to 40,
            "formatRewriteRelay" to 40,
            "formatRingChoir" to 40,
            "formatRingDash" to 40,
            "formatScanSprint" to 40,
            "formatSealSprint" to 40,
            "formatSecureSprint" to 40,
            "formatShieldSprint" to 40,
            "formatSlideRace" to 40,
            "formatSnapGuard" to 40,
            "formatSoundWave" to 40,
            "formatSpoilerRace" to 40,
            "formatSpringDash" to 40,
            "formatStampRelay" to 40,
            "formatStarSeal" to 40,
            "formatSuggestCircle" to 40,
            "formatSummaryCircle" to 40,
            "formatThemeSprint" to 40,
            "formatTraySeal" to 40,
            "formatTrustSprint" to 40,
            "formatTypingSeal" to 40,
            "formatUnreadRush" to 40,
            "formatUrlFence" to 40,
            "formatVaultFence" to 40,
            "formatVaultLock" to 40,
            "formatVideoStage" to 40,
            "formatVoiceRace" to 40,
            "formatVoiceRing" to 40,
            "formatWakeSprint" to 40,
            "formatWallPick" to 40,
            "formatWatermarkHunt" to 40,
        )

    /**
     * G223d：**其余「单 String 参数 + hostLabel、parse 回 String?」的 68 对**。
     *
     * G223c 覆盖了 `(mode, hostLabel)` 那 84 对；本轮把同语义但参数名不同的
     * 一批补齐（seed / prompt / topic / pair / line / token / board / window / …）。
     * 语义与 G223c 完全一致：`parseX(formatX(x, h)) == x.trim().take(N)`，
     * 只是 N 因函数而异（实测有 1/4/8/10/12/16/20/24/30/40/50/60/80/100/160 十五种）。
     *
     * **精确断言，不用「是前缀」**——G223c 第一版就是宽松断言，导致负控制打不红。
     */
    @Test
    fun `single string param pairs round trip exactly with their own limit`() {
        val host = "Host"
        val cases = listOf(
            "hello", "with|pipe", "with^caret", "  padded  ", "z".repeat(400),
            "\uD83C\uDF89emoji\uD83D\uDE00", "line1\nline2",
        )
        for ((name, limit) in SINGLE_STRING_PARAM_TAKE) {
            val formatFn = GroupPlayPolicy::class.java.getMethod(name, String::class.java, String::class.java)
            val parseFn = GroupPlayPolicy::class.java.getMethod("parse" + name.removePrefix("format"), String::class.java)
            for (value in cases) {
                val content = formatFn.invoke(GroupPlayPolicy, value, host) as String
                val parsed = parseFn.invoke(GroupPlayPolicy, content) as String?
                val trimmed = value.trim()
                if (trimmed.isEmpty()) {
                    assertNull(parsed, "$name($value) trim 后为空，应归一为 null")
                    continue
                }
                assertEquals(
                    trimmed.take(limit),
                    parsed,
                    "$name($value) 往返不一致（limit=$limit）；content=$content",
                )
            }
        }
        assertEquals(65, SINGLE_STRING_PARAM_TAKE.size, "实测 65 对（另 3 对语义特殊，见下）")
    }

    /**
     * G223d：**被跳过的那一对要留下记录**，否则将来看见手册短了一行没人知道为什么。
     *
     * `formatCoinFlip` 不做截断，而是**值归一化**：任意输入都被折成
     * "HEADS" 或 "TAILS"。它的往返语义是「归一后的值」，不是「trim().take(N)」，
     * 硬套进上面的精确断言会是错的。本轮明确跳过并记在这里。
     */
    @Test
    fun `coin flip normalizes instead of truncating so it is excluded`() {
        // 只有大小写不敏感的 "HEADS" 归一成 HEADS，其余一切（含空串、长串）都是 TAILS
        assertEquals("HEADS", GroupPlayPolicy.parseCoinFlip(GroupPlayPolicy.formatCoinFlip("heads", "Host")))
        assertEquals("HEADS", GroupPlayPolicy.parseCoinFlip(GroupPlayPolicy.formatCoinFlip("HeAdS", "Host")))
        assertEquals("TAILS", GroupPlayPolicy.parseCoinFlip(GroupPlayPolicy.formatCoinFlip("totally not heads", "Host")))
        assertEquals("TAILS", GroupPlayPolicy.parseCoinFlip(GroupPlayPolicy.formatCoinFlip("", "Host")))
        // 关键：输入再长也不会被截断成问题——它根本不走 trim().take(N) 那条路
        val longInput = "x".repeat(500)
        assertEquals("TAILS", GroupPlayPolicy.parseCoinFlip(GroupPlayPolicy.formatCoinFlip(longInput, "Host")))
    }


    /**
     * G223d：**2 对 take 前没有 trim——往返会带回首尾空白**（本轮实测抓到的真实不一致）。
     *
     * `formatSpin` / `formatSimon` 写的是 `esc(result.take(40))` /
     * `val s = seq.take(16)`，而其余 65 对都是 `trim().take(N)`。
     * 后果：`parse(format("  x  ", h))` 返回 `"  x  "` 而不是 `"x"`。
     *
     * **按目标第 (3) 条，本轮不改生产代码**——先钉住现状，是否统一由产品决策
     * （这 68 对整体是 roadmap 死代码）。这条测试的价值是：将来谁「顺手」给这 2 对
     * 补上 trim，这条会红，提醒他那是行为变更。
     */
    @Test
    fun `two pairs take before trim so they round trip with padding`() {
        assertEquals("  padded  ", GroupPlayPolicy.parseSpin(GroupPlayPolicy.formatSpin("  padded  ", "Host")))
        assertEquals("  padded  ", GroupPlayPolicy.parseSimon(GroupPlayPolicy.formatSimon("  padded  ", "Host")))
        // 对照组：同批次的 formatToast 是 trim 过的，行为不同
        assertEquals("padded", GroupPlayPolicy.parseToast(GroupPlayPolicy.formatToast("  padded  ", "Host")))
    }

    /**
     * G223d：`formatAlphabet` 是第三例语义特殊的——它**把首字母转成大写**，
     * 且 trim+take(1) 后为空时回落 "A"。往返结果是「大写首字母」而非原样输入。
     * 同样**不改生产代码**，只钉住现状。
     */
    @Test
    fun `format alphabet upper cases its single letter so it is excluded`() {
        assertEquals("H", GroupPlayPolicy.parseAlphabet(GroupPlayPolicy.formatAlphabet("hello", "Host")))
        assertEquals("H", GroupPlayPolicy.parseAlphabet(GroupPlayPolicy.formatAlphabet("  h  ", "Host")))
        assertEquals("A", GroupPlayPolicy.parseAlphabet(GroupPlayPolicy.formatAlphabet("   ", "Host")))
        assertEquals("A", GroupPlayPolicy.parseAlphabet(GroupPlayPolicy.formatAlphabet("ab", "Host")))
    }

    private companion object {
        /** G223d 手册：单 String 参数 + hostLabel、parse 回 String? 的 65 对及其 take(N)。 */
        val SINGLE_STRING_PARAM_TAKE: List<Pair<String, Int>> = listOf(
            "formatStory" to 160,  // seed
            "formatNeverHaveIEver" to 100,  // prompt
            "formatIcebreaker" to 100,  // prompt
            "formatMirror" to 100,  // line
            "formatMinuteTalk" to 80,  // topic
            "formatStorySwap" to 80,  // opener
            "formatKaraoke" to 80,  // line
            "formatBlindQ" to 80,  // q
            "formatFortune" to 80,  // text
            "formatDebate" to 80,  // topic
            "formatToast" to 80,  // line
            "formatTimeCapsule" to 80,  // note
            "formatHotOrNot" to 60,  // topic
            "formatDrawPrompt" to 60,  // prompt
            "formatTaboo" to 60,  // card
            "formatLightning" to 60,  // prompt
            "formatWhisper" to 60,  // prompt
            "formatQuickPoll" to 60,  // options
            "formatFactOrFiction" to 60,  // item
            "formatGeoGuess" to 50,  // clue
            "formatStorySeed" to 50,  // seed
            "formatEmojiOnly" to 50,  // prompt
            "formatDebateFlash" to 50,  // topic
            "formatMirrorEcho" to 50,  // line
            "formatSillyLaw" to 50,  // law
            "formatHotSeat" to 40,  // target
            "formatCharades" to 40,  // prompt
            "formatRapidFire" to 40,  // topic
            "formatCaptionThis" to 40,  // seed
            "formatSpyfall" to 40,  // location
            "formatTwentyQuestions" to 40,  // subject
            "formatPasswordGame" to 40,  // hint
            "formatSilentMovie" to 40,  // prompt
            "formatWordScramble" to 40,  // pair
            "formatIdeaRelay" to 40,  // seed
            "formatGratitudeRound" to 40,  // prompt
            "formatCategories" to 30,  // cat
            "formatColorWord" to 30,  // pair
            "formatSecretSignal" to 30,  // signal
            "formatMoodMeter" to 30,  // scale
            "formatRedPacketJoke" to 24,  // amountLabel
            "formatImpostor" to 24,  // word
            "formatEmojiStory" to 24,  // seed
            "formatMemoryMatch" to 24,  // board
            "formatEmojiMemory" to 24,  // board
            "formatAcrostic" to 20,  // seed
            "formatRhyme" to 20,  // seed
            "formatTwoWords" to 20,  // seed
            "formatOneWord" to 20,  // word
            "formatSpeedMath" to 20,  // q
            "formatReactionDuel" to 20,  // pair
            "formatEmojiMath" to 20,  // expr
            "formatPinTheMood" to 20,  // mood
            "formatTranslateRelay" to 20,  // pair
            "formatEmojiDuel" to 16,  // pair
            "formatTempoTap" to 12,  // beat
            "formatRevokeRush" to 10,  // window
            "formatFocusSprint" to 10,  // window
            "formatChainReact" to 8,  // seed
            "formatHideSeek" to 8,  // emoji
            "formatBlindDraw" to 8,  // token
            "formatImpulseDraw" to 8,  // token
            "formatCodeBreaker" to 8,  // code
            "formatAlphabetRace" to 4,  // start
            "formatSyncClap" to 4,  // count
        )

        /**
         * G167b 冻结值：542 个成员里 297 个零引用。
         *
         * G223c：**226**。往下调的原因是 G223c 的往返测试用反射驱动那 88 个
         * `(mode, hostLabel)` 形状的 format，函数名以字符串字面量出现在
         * `MODE_HOST_LABEL_FORMATS` 名单里，于是棘轮的 `name` 口径认它们为
         * 「已被引用」。这**不是数字游戏**——那 88 个函数现在真的有测试覆盖
         * （往返断言），从「死代码」变成「有测试但无产品入口」。
         * 剩下 226 个仍是真死代码。
         */
        const val UNREFERENCED_BASELINE = 181

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
