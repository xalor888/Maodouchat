package com.maodouchat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.maodouchat.ai.AiConversationProfileSource
import com.maodouchat.conversation.ReadReceiptSource

/**
 * G63：轨道 C（客户端热点）的**前置边界门禁**。
 *
 * 客户端热点有一条既定原则：「这些是本项目最贵的债，但**放在最后做**：在边界门禁就位之前动它们，
 * 只会重演『表面收敛、实质未动』。」本轮就是把这句话变成可执行断言——先立门槛，再谈重构。
 *
 * 三条硬约束，全部做成**棘轮**（冻结当前实测值，只许降不许升），与 `ServerArchitectureTest` 同一思路：
 *
 * 1. `ui/` 不得直接引用 `data.local.dao`（DAO/Room 写路径只能经 ViewModel / repository）。
 *    当前实测只有 1 处违规——所以这里是**精确名单**而不是类别禁止：
 *    清掉那一处后名单变空，此后任何新增都会立刻红。
 * 2. 三个热点文件的行数上限冻结在当前值（只许降）：重构把文件拆小 → 通过；越改越胖 → 红。
 * 3. `GroupPlayPolicy` 不得出现同名重复文件（历史遗留债，精确名单见下方断言）。
 *
 * 为什么用**源码文本**而不是 ArchUnit：app 模块没有 ArchUnit 依赖，而这三条判据
 * （import 面、文件行数、同名重复）本来就是源码属性；用文本断言不引入新依赖，
 * 也不会因为类加载不到而静默跳过。
 */
class ClientArchitectureTest {

    private val repoRoot: File = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        checkNotNull(dir) { "找不到仓库根（含 settings.gradle.kts）" }
    }

    private val appMain = File(repoRoot, "app/src/main/java")

    private fun ktFilesUnder(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ─── 1. ui/ 不得直连 DAO（精确名单棘轮） ───

    /**
     * `ui/` 下直接 import `com.maodouchat.data.local.dao.*` 的文件。
     *
     * G64 已把最后那一处（`ChatReadReceiptCoordinator` 的 `MessagingV2Dao`）改成经
     * `ReadReceiptSource` 端口注入，因此这里是**空名单**——`ui/` 层从此不允许直连任何 DAO。
     * 名单的历史值见 git；把它改回非空等于把棘轮放松，反向断言会抓住那种改动。
     */
    /**
     * `while (true)` 循环体里允许出现的挂起标记。
     *
     * `delay(` 是 LaunchedEffect 里的常见写法，但**不是唯一**：Compose 手势的
     * `awaitEachGesture { while(true) { awaitPointerEvent() } }` 靠 `await*` 挂起，
     * 渠道/流的 `while (true) { receive() }` 靠 `receive` 挂起。只看 delay 会把这些
     * 官方模式误判成「卡死循环」——所以列全，宁可放过不可误杀。
     */
    private val SUSPEND_MARKERS = listOf(
        "delay(",
        "awaitPointerEvent(",
        "awaitFirstDown(",
        "awaitEachGesture(",
        "receive(",
        "withContext(",
        "withTimeout(",
        "yield(",
        "suspend ",
    )

    private val frozenUiDaoImporters: List<String> = emptyList()

    /**
     * G328c：判据从「import 行以 `com.maodouchat.data.local.dao` 开头」加强为
     * 「剥掉注释与字符串内容后，源码里出现这个包名」。
     *
     * 旧判据的缺口是**全限定名直接调用**：`com.maodouchat.data.local.dao.ChatDao(db).all()`
     * 完全不需要 import，一行 `startsWith("import ...")` 看不见它。
     * 改成在「剥注释 + 剥字符串内容」后的源码上找包名本身，引用方式怎么变都躲不开；
     * 顺带把 `import ... as X` 别名一并覆盖（旧判据其实已经能抓到，这里不再依赖那个巧合）。
     * 必须剥字符串：包名出现在字符串里（日志、文档、SQL）不算引用。
     */
    private fun stripCommentsAndStringBodies(text: String): String {
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
                    // 保留引号本身，丢掉内容
                    if (c == '\\') { i++ } else if (c == '"') { state = 0; out.append(c) }
                }
                4 -> {
                    if (c == '\\') { i++ } else if (c == '\'') { state = 0; out.append(c) }
                }
            }
            i++
        }
        return out.toString()
    }

    @Test
    fun `ui must not import data-local daos and the list may only shrink`() {
        val offenders = ktFilesUnder(File(appMain, "com/maodouchat/ui"))
            .filter { file ->
                stripCommentsAndStringBodies(file.readText()).contains("com.maodouchat.data.local.dao")
            }
            .map { it.relativeTo(appMain).path.replace('\\', '/') }
            .sorted()

        val unexpected = offenders - frozenUiDaoImporters.toSet()
        assertEquals(
            emptyList(),
            unexpected,
            "ui/ 新增了直连 DAO 的文件——写库只能经 ViewModel/repository。" +
                "若这是**减少**违规，请同步下调 frozenUiDaoImporters。实际=$unexpected",
        )
        val fixed = frozenUiDaoImporters - offenders.toSet()
        assertEquals(
            emptyList(),
            fixed,
            "有文件已经不再直连 DAO（好事）——请把它从 frozenUiDaoImporters 里删掉，让棘轮收紧。",
        )
    }

    // ─── 1b. 包级分层：非 ui 包不得 import ui（G328c） ───

    /**
     * 唯一允许「非 ui 包 → ui 包」的两个例外，附理由。
     *
     * 这条规则来自审计：此前有 **11 个**非 ui 文件 import `com.maodouchat.ui.*`，
     * 其中真正不合理的是 `notification/` 的通知服务（为了一个常量/一个纯文本函数被迫
     * 依赖 UI 包）、`group/` 的生命周期服务（依赖 ui 里的协调器）、`explore/usecase`
     * （依赖 ui 里的 policy 与 port）、以及一个住在 `ai/agent/` 里的 Compose 屏幕。
     * 这些已在同一次提交里逐类修掉：把纯逻辑（端口、契约、正则、文本处理）搬到中立包，
     * 把属于 UI 的文件搬回 ui/。
     *
     * 剩下的两个是**架构上正确**的方向，不是倒置：
     * - `MainActivity.kt`：它就是要承载 UI 的入口 Activity；
     * - `navigation/` 下的那些 Destinations 文件：导航图必须引用它注册的 Composable 屏幕。
     *   （含 @Composable 的导航文件仍在 ui/navigation；纯路由契约已抽到 `navigation/`）
     */
    private val uiImportAllowedFromOutsideUi: Map<String, String> = mapOf(
        "com/maodouchat/MainActivity.kt" to "入口 Activity，承载 UI 是其职责",
        "com/maodouchat/navigation/CallDestinations.kt" to
            "导航图注册点必须引用 Composable 屏幕（IncomingCallRoute 留在 ui/navigation）",
    )

    @Test
    fun `packages outside ui must not import ui`() {
        val root = File(appMain, "com/maodouchat")
        val offenders = ktFilesUnder(File(appMain, "com/maodouchat"))
            .filter { it.relativeTo(root).invariantSeparatorsPath.substringBefore('/') != "ui" }
            .filter { file ->
                stripComments(file.readText()).lines().any { it.startsWith("import com.maodouchat.ui") }
            }
            .map { it.relativeTo(appMain).path.replace('\\', '/') }
            .filterNot { it in uiImportAllowedFromOutsideUi.keys }
            .sorted()
        assertEquals(
            emptyList(),
            offenders,
            "这些非 ui 包的文件 import 了 com.maodouchat.ui.*——分层倒置。" +
                "纯逻辑请搬到中立包（如 navigation/、messaging/、explore/policy），" +
                "属于 UI 的文件请搬回 ui/；确实合理的要加进 uiImportAllowedFromOutsideUi 并写明理由。实际=$offenders",
        )
    }

    // ─── 2. 热点文件行数冻结（只许降） ───

    /**
     * 三个热点文件的**行数上限**，取 G63 开工时的实测值。
     *
     * 语义是「不得再增长」而不是「必须小于某个数」：重构拆小 → 上限自动有余量；
     * 但任何人往这些文件里堆代码 → 立刻红。要收紧上限，改小这里的数字即可。
     */
    /** G164b：按行数排名监控前多少个文件。改大 = 管得更宽，但要同步补上限。 */
    private val MONITORED_TOP_N = 20

    private val frozenHotspotLineCaps: Map<String, Int> = mapOf(
        // G328c：本轮「包级分层」重构（把端口/契约/纯逻辑从 ui 搬到中立包）给若干文件
        // 加了 import 行，这 7 个上限因此按**实测值**同步上调（+1..+6 行；MarkdownMessage
        // 与 MarkdownParser 是拆出 ChatMarkdown 后的净**下降**）。
        // 棘轮方向不变：从这里开始只许降。上调的原因是必要的 import，不是往里堆逻辑。

        "com/maodouchat/ui/screen/chatdetail/ChatDetailRoute.kt" to 3432,
        "com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt" to 3071,
        "com/maodouchat/util/GroupPlayPolicy.kt" to 1945,
        // G113：以下六个文件此前**没有任何行数门禁**，是 app 内剩下的大文件。
        // 纳入棘轮，之后每拆一块就往下调。
        "com/maodouchat/ui/screen/chatdetail/ChatDetailAiGeneration.kt" to 340,
        "com/maodouchat/ui/screen/settings/SettingsAccountSecurity.kt" to 672,
        "com/maodouchat/ui/screen/call/CallViewModel.kt" to 1651,
        "com/maodouchat/ui/screen/explore/ExploreFeedScreen.kt" to 638,
        // G126：以下七个文件此前**没有任何行数门禁**（其中 ChatDetailMiscDialogs.kt 是
        // G108 我自己拆出来的——拆完不纳管，等于给新热点留了门）。纳入后 app 内
        // 1100+ 行源文件全部在监。
        "com/maodouchat/webrtc/WebRTCManager.kt" to 1416,
        "com/maodouchat/ui/screen/chatlist/ChatListScreen.kt" to 432,
        "com/maodouchat/ui/screen/settings/SettingsViewModel.kt" to 1271,
        "com/maodouchat/ui/screen/contacts/ContactsListScreen.kt" to 666,
        "com/maodouchat/ui/component/MarkdownMessage.kt" to 162,
        // G172：vendored 的 Compose 图标文件（androidx 包，非本项目代码）；
        // 补上它之后「app 内 1100+ 行源文件全部在监」才真正成立。
        "androidx/compose/material/icons/outlined/ExtendedOutlinedIcons.kt" to 2678,
        // G163b：监控阈值从 1100 降到 1000。这 5 个文件此前卡在 1000–1100 的
        // **盲带**里——可以在无人知晓的情况下从 1000 长到 1100，只有越过 1100
        // 才会被 G172 那条抓住，那已经太晚。按当前实测值冻结，只许降不许升。
        "com/maodouchat/ui/component/TextMessageBubble.kt" to 1050,
        "com/maodouchat/ui/screen/chatdetail/MediaCenterScreen.kt" to 1066,
        "com/maodouchat/ui/screen/explore/ExploreOrchestrator.kt" to 1038,
        "com/maodouchat/ui/screen/chatdetail/GroupDetailViewModel.kt" to 998,
        "com/maodouchat/ui/screen/chatlist/GlobalSearchScreen.kt" to 1010,
        // G164b：监控判据从「>1000 行」换成「行数排名前 20」，这 8 个原本在 1000 以下的
        // 文件随之进入监管范围。按当前实测值冻结，只许降不许升。
        "com/maodouchat/network/WebSocketClient.kt" to 974,
        "com/maodouchat/ui/component/MarkdownParser.kt" to 966,
        "com/maodouchat/ui/screen/chatdetail/ChatDetailComposerExtras.kt" to 945,
        "com/maodouchat/ui/theme/Motion.kt" to 939,
        "com/maodouchat/ui/screen/settings/SettingsScreen.kt" to 936,
        "com/maodouchat/network/api/ApiEndpointClients.kt" to 923,
        "com/maodouchat/ui/screen/contacts/ContactSubScreens.kt" to 900,
        "com/maodouchat/ui/screen/settings/SettingsAccountSecurityScreen.kt" to 888,
    )

    @Test
    fun `client hotspot files may not grow`() {
        val grown = mutableListOf<String>()
        frozenHotspotLineCaps.forEach { (relativePath, cap) ->
            val file = File(appMain, relativePath)
            assertTrue("热点文件必须存在：$relativePath") { file.isFile }
            val lines = file.readLines().size
            if (lines > cap) grown += "$relativePath: $lines 行 > 上限 $cap 行"
        }
        assertEquals(emptyList(), grown, "热点文件变大了——先拆再改，别往债里加码")
    }

    @Test
    fun `hotspot line caps only ever shrink`() {
        // 反向棘轮：如果有人**调大**了上限来放行更大的文件，这里会红。
        // 上限只能往下调（收紧），往上调必须是真的先删了代码。
        //
        // 注意：下面这份 map 是本文件里 frozenHotspotLineCaps 的**副本**，只用于
        // 「两份相等」这条弱断言（见下方 G165 的说明——真正的外部基线是与 git HEAD 比）。
        // 改上限时要**两处一起改**，否则这条会红而 G165 那条不红，容易误判。
        val currentCaps = mapOf(
            "com/maodouchat/ui/screen/chatdetail/ChatDetailRoute.kt" to 3432,
            "com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt" to 3071,
            "com/maodouchat/util/GroupPlayPolicy.kt" to 1945,
            "com/maodouchat/ui/screen/chatdetail/ChatDetailAiGeneration.kt" to 340,
                "com/maodouchat/ui/screen/settings/SettingsAccountSecurity.kt" to 672,
            "com/maodouchat/ui/screen/call/CallViewModel.kt" to 1651,
            "com/maodouchat/ui/screen/explore/ExploreFeedScreen.kt" to 638,
        // G126：以下七个文件此前**没有任何行数门禁**（其中 ChatDetailMiscDialogs.kt 是
            // G108 我自己拆出来的——拆完不纳管，等于给新热点留了门）。纳入后 app 内
            // 1100+ 行源文件全部在监。
            "com/maodouchat/webrtc/WebRTCManager.kt" to 1416,
            "com/maodouchat/ui/screen/chatlist/ChatListScreen.kt" to 432,
            "com/maodouchat/ui/screen/settings/SettingsViewModel.kt" to 1271,
                "com/maodouchat/ui/screen/contacts/ContactsListScreen.kt" to 666,
            "com/maodouchat/ui/component/MarkdownMessage.kt" to 162,
        // G172：vendored 的 Compose 图标文件（androidx 包，非本项目代码）；
        // 补上它之后「app 内 1100+ 行源文件全部在监」才真正成立。
        "androidx/compose/material/icons/outlined/ExtendedOutlinedIcons.kt" to 2678,
        // G163b：阈值下探到 1000 后补入的 5 个（此前在 1000–1100 盲带里）
        "com/maodouchat/ui/component/TextMessageBubble.kt" to 1050,
        "com/maodouchat/ui/screen/chatdetail/MediaCenterScreen.kt" to 1066,
        "com/maodouchat/ui/screen/explore/ExploreOrchestrator.kt" to 1038,
        "com/maodouchat/ui/screen/chatdetail/GroupDetailViewModel.kt" to 998,
        "com/maodouchat/ui/screen/chatlist/GlobalSearchScreen.kt" to 1010,
        // G164b：Top-20 排名门禁纳入的 8 个
        "com/maodouchat/network/WebSocketClient.kt" to 974,
        "com/maodouchat/ui/component/MarkdownParser.kt" to 966,
        "com/maodouchat/ui/screen/chatdetail/ChatDetailComposerExtras.kt" to 945,
        "com/maodouchat/ui/theme/Motion.kt" to 939,
        "com/maodouchat/ui/screen/settings/SettingsScreen.kt" to 936,
        "com/maodouchat/network/api/ApiEndpointClients.kt" to 923,
        "com/maodouchat/ui/screen/contacts/ContactSubScreens.kt" to 900,
        "com/maodouchat/ui/screen/settings/SettingsAccountSecurityScreen.kt" to 888,
                )
        assertEquals(currentCaps, frozenHotspotLineCaps, "热点文件上限被改动了——收紧可以，放宽不行")
    }

    /**
     * G214b：**余量必须为零**——棘轮只防「长大」，不防「悄悄松动」。
     *
     * 原判据是 `lines > cap` 就红。于是**把文件拆小之后上限不会自动收紧**：
     * 拆掉 40 行、上限仍是旧值，测试照样绿，而那 40 行余量从此可以被人
     * 无声地加回来——「只许降不许升」在现实里退化成了「不许升，但可以偷偷回到原点」。
     *
     * 这条断言把语义补成「上限必须恒等于实测行数」：
     * 任何收缩都必须**在同一个提交里**同步收紧上限，否则这里红。
     */
    @Test
    fun `hotspot caps have zero slack`() {
        val slack = mutableListOf<String>()
        frozenHotspotLineCaps.forEach { (relativePath, cap) ->
            val file = File(appMain, relativePath)
            assertTrue("热点文件必须存在：$relativePath") { file.isFile }
            val lines = file.readLines().size
            if (lines != cap) {
                slack += "$relativePath: 实测 $lines 行 ≠ 上限 $cap 行（余量 ${cap - lines}）"
            }
        }
        assertEquals(
            emptyList(), slack,
            "热点文件的上限与实测不一致——拆小了就顺手把上限收紧，别留余量",
        )
    }

    /**
     * G164b：**覆盖性**检查——按行数排名前 [MONITORED_TOP_N] 的源文件必须全部已有上限。
     *
     * 这里的判据从「超过 N 行」换成了「排名前 N」，**换掉了那个要人工反复调的阈值**：
     * G172 定 1100、G163b 下探到 1000，每次下调都有一批新文件落进盲带——
     * 那是无限回归，因为阈值本身没有天然停点。
     *
     * 换成排名后语义变成「最大的那些文件必须全部有上限」：
     * - 某文件被拆小、掉出前 20 → 它的上限仍在表里（依然只许降），不需要动门禁；
     * - 某文件长大、挤进前 20 → **自动**要求纳管，不需要任何人想起「调阈值」这件事。
     */
    @Test
    fun `the largest app source files are all under a frozen cap`() {
        val bySize = ktFilesUnder(appMain)
            .map { it.relativeTo(appMain).path.replace('\\', '/') to it.readLines().size }
            .sortedByDescending { it.second }
            .take(MONITORED_TOP_N)
        val unmonitored = bySize.map { it.first }.filterNot { it in frozenHotspotLineCaps }
        assertEquals(
            emptyList<String>(),
            unmonitored,
            "这些文件已进行数前 $MONITORED_TOP_N 名却没有纳管——先拆，或至少加进行数上限表",
        )
    }

    /**
     * G165：真正的外部基线棘轮。
     *
     * 上面那条 `hotspot line caps only ever shrink` 只比较**同一文件里**的两份 mapOf，
     * 把两处一起改大它们依然相等——G164 的负控制证明了它抓不到「故意放宽」。
     *
     * 这里改成与 **git HEAD 里的上一版**解析结果比较：任何跨提交的放宽都会红。
     * 收紧自由（拆分后往下调是该鼓励的），放宽必须是真的先删了代码。
     *
     * 降级策略：git 不可用 / 本文件未被跟踪（如首次提交前）时**跳过**而非误报。
     */
    @Test
    fun `hotspot line caps may not grow across commits`() {
        val previous = previousCapsFromGitHead() ?: run {
            println("SKIP hotspot line caps may not grow across commits：拿不到 git HEAD 基线")
            return
        }
        val grown = mutableListOf<String>()
        frozenHotspotLineCaps.forEach { (path, cap) ->
            val before = previous[path] ?: return@forEach // 新纳入监管的文件，无基线可比
            if (cap > before) grown += "$path: 上限 $before -> $cap（放宽了 ${cap - before} 行）"
        }
        assertEquals(emptyList(), grown, "热点上限被放宽了——只能收紧；要放宽必须先真的删掉代码")
    }

    /**
     * 从 git HEAD 读上一版 ClientArchitectureTest.kt，解析出 frozenHotspotLineCaps。
     * 任何失败都返回 null（调用方据此跳过，而不是误报）。
     */
    private fun previousCapsFromGitHead(): Map<String, Int>? = runCatching {
        val rel = "app/src/test/java/com/maodouchat/ClientArchitectureTest.kt"
        val pb = ProcessBuilder("git", "show", "HEAD:$rel")
            .directory(repoRoot)
            .redirectErrorStream(true)
        val out = pb.start().inputStream.bufferedReader().readText()
        if (out.isBlank() || out.startsWith("fatal:")) return null
        val start = out.indexOf("private val frozenHotspotLineCaps")
        if (start < 0) return null
        val blk = out.substring(start, out.indexOf(')', out.indexOf("mapOf(", start)))
        val pattern = Regex(""""([^"]+)"\s*to\s*(\d+)""")
        pattern.findAll(blk)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ─── 3. GroupPlayPolicy 不得有同名重复文件 ───

    @Test
    fun `GroupPlayPolicy exists exactly once in main sources`() {
        val matches = repoRoot.walkTopDown()
            .filter { it.isFile && it.name == "GroupPlayPolicy.kt" && it.path.contains("/src/main/") }
            .map { it.relativeTo(repoRoot).path }
            .toList()
        assertEquals(
            listOf("app/src/main/java/com/maodouchat/util/GroupPlayPolicy.kt"),
            matches.sorted(),
            "GroupPlayPolicy 出现同名重复文件——这个债必须只有一个",
        )
    }

    // ─── 4. 反假绿：门禁本身必须真的在扫东西 ───

    @Test
    fun `the gate actually scans the ui tree`() {
        val uiFiles = ktFilesUnder(File(appMain, "com/maodouchat/ui"))
        assertTrue(
            "ui/ 下应当有几百个 Kotlin 文件，实际 ${uiFiles.size}——扫描范围不对，本条门禁是空的"
        ) { uiFiles.size > 100 }
        assertTrue("至少有一个热点文件存在，否则行数上限断言是空转") {
            frozenHotspotLineCaps.keys.all { File(appMain, it).isFile }
        }
    }
    // ─── 5. Composable 不得直接抓 app 单例的 database（G71） ───

    /**
     * `ui/` 下直接抓 `MaodouchatApp.database` 的文件（Composable 内落库的典型形态）。
     *
     * `ChatDetailRoute.kt` 的「单个超长 composable」是客户端最贵的债之一。
     * 实测该文件里有 **4 处** `(appContext as MaodouchatApp).database`——全在 `LaunchedEffect` 里、
     * 也都包了 `withContext(Dispatchers.IO)`，所以**不会崩**；但它们是「UI 层直接拿数据库」的
     * 完整样本：读什么表、什么时候读，UI 自己决定，ViewModel 完全不知道。
     *
     * 用精确名单而不是类别禁止：清掉一处就短一处，最终应变空；
     * 反向断言保证「已清掉的必须删出名单」，棘轮只会收紧。
     */
    /**
     * G73 把 `ChatDetailRoute.kt` 里最后 4 处也清掉了：4 个 AI 能力（会话画像/分类/情感回复/周报）
     * 各自端口化（`AiConversationProfileSource` 等，端口在 `ui/`、实现在 `data/local/Room*Source`），
     * 由 `ChatDetailViewModel` 装配后经参数传给 Route。
     *
     * G328c：这份名单**不再是空的**——因为判据修好了之后，实测发现它此前在说谎。
     * 旧判据是两条字面量 `contains("as com.maodouchat.MaodouchatApp).database")` 与
     * `contains("MaodouchatApp.database")`，当场就漏掉一个真实违规：
     * `ContactsViewModel.kt` 写的是 `(application as MaodouchatApp).database.userDao()`——
     * 非全限定转型 + 括号，两条字面量都不匹配。换成两条正则后，ui/ 下实测有 9 个文件
     * 仍在从 app 容器取 `database` 造 DAO/Repository。
     *
     * 这 9 个是**真实的待还债**，不是豁免：它们要经 B02「依赖注入装配」改成构造器注入。
     * 棘轮只许降（下面的反向断言会强制：修好一个就必须从名单里删掉）。
     */
    private val frozenUiAppDatabaseGrabbers: List<String> = listOf(
        "com/maodouchat/ui/screen/chatdetail/AiTasksScreen.kt",
        "com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt",
        "com/maodouchat/ui/screen/chatdetail/MediaCenterScreen.kt",
        "com/maodouchat/ui/screen/chatdetail/StarredMessagesScreen.kt",
        "com/maodouchat/ui/screen/chatlist/ChatListPorts.kt",
        "com/maodouchat/ui/screen/chatlist/GlobalSearchScreen.kt",
        "com/maodouchat/ui/screen/contacts/ContactSubScreens.kt",
        "com/maodouchat/ui/screen/contacts/ContactsRepository.kt",
        "com/maodouchat/ui/screen/contacts/ContactsViewModel.kt",
    )

    /**
     * G328c：判据从两条**字面量**换成正则。
     *
     * 形态 1：静态/伴随对象取用 `MaodouchatApp.database`。
     * 形态 2：两步形态——文件里既有 `as [com.maodouchat.]MaodouchatApp` 转型、又出现
     * `.database` 成员访问（`val app = application as MaodouchatApp` 之后 `app.database`，
     * 单看一行抓不到，而这正是那 9 个文件里的主要写法）。
     *
     * 形态 2 偏宽：理论上「为了别的原因转型 + 别处用到别的 .database」会误报。这是有意的取舍——
     * 宁可让人来名单里写一句理由，也不要再留一个看不见的缺口：这个闸门的全部价值就在这里。
     */
    private val appDatabaseGrabPatterns: List<Regex> = listOf(
        Regex("""\bMaodouchatApp\s*\.\s*database\b"""),
        Regex("""\bas\s+(?:com\.maodouchat\.)?MaodouchatApp\b[\s\S]*?\.\s*database\b"""),
    )

    private fun grabsAppDatabase(text: String): Boolean =
        appDatabaseGrabPatterns.any { it.containsMatchIn(text) }

    @Test
    fun `ui must not grab the app database singleton and the list may only shrink`() {
        val grabbers = ktFilesUnder(File(appMain, "com/maodouchat/ui"))
            .filter { file ->
                // G168b：必须剥注释——否则「我们不抓单例」这类 KDoc 会把文件计成违规
                // （G156b 就这样虚增了 48 个文件）。
                grabsAppDatabase(stripComments(file.readText()))
            }
            .map { it.relativeTo(appMain).path.replace('\\', '/') }
            .sorted()

        val unexpected = grabbers - frozenUiAppDatabaseGrabbers.toSet()
        assertEquals(
            emptyList(),
            unexpected,
            "ui/ 新增了直接抓 app 数据库单例的文件——读库只能经 ViewModel/repository。" +
                "若这是**减少**违规，请同步下调 frozenUiAppDatabaseGrabbers。实际=$unexpected",
        )
        val fixed = frozenUiAppDatabaseGrabbers - grabbers.toSet()
        assertEquals(
            emptyList(),
            fixed,
            "有文件已经不再直连数据库（好事）——请把它从 frozenUiAppDatabaseGrabbers 里删掉，让棘轮收紧。",
        )
    }

    // ─── 6. while(true) 必须包在 LaunchedEffect 里且带 delay（G71） ───

    /**
     * 热点文件里的裸 `while (true)` 是主线程死循环的经典形态。
     *
     * `ChatDetailRoute.kt`「内含 while(true) 每 60s 写库」是已知的 UI 内落库样本。实测只有一处，
     * 且它**合规**：包在 `LaunchedEffect` 里、循环体第一件事就是 `delay(10 * 60 * 1000L)`。
     *
     * 这条断言锁住两件事：① 每个 `while (true)` 都必须在一个 `LaunchedEffect {` 之后出现；
     * ② 该循环体内必须含 `delay(`。少了任何一条，协程取消就不再生效，UI 直接卡死。
     */
    @Test
    fun `every bare while true loop lives in a LaunchedEffect and yields with delay`() {
        val violations = mutableListOf<String>()
        frozenHotspotLineCaps.keys.forEach { relativePath ->
            val lines = File(appMain, relativePath).readLines()
            // 先用花配平算出「每一行是否处于 LaunchedEffect 块内」——只看上方最近的一次出现
            // 是不够的：文件后半部分的行也会「找到」前半部分的 LaunchedEffect，从而假合规。
            var depthInEffect = 0
            val inEffect = BooleanArray(lines.size)
            lines.forEachIndexed { index, line ->
                // 先按本行判定，再更新深度：这样「LaunchedEffect(...) {」这一行本身算在块内
                inEffect[index] = depthInEffect > 0 || line.contains("LaunchedEffect")
                depthInEffect += line.count { it == '{' } - line.count { it == '}' }
                if (depthInEffect < 0) depthInEffect = 0
            }
            lines.forEachIndexed { index, line ->
                if (!line.contains("while (true)")) return@forEachIndexed
                val loopLine = index + 1
                if (!inEffect[index]) {
                    violations += "$relativePath:$loopLine 的 while(true) 不在任何 LaunchedEffect 块内"
                    return@forEachIndexed
                }
                // 循环体必须满足二者之一：
                // (a) 含**挂起**点（delay / awaitPointerEvent / awaitFirstDown / receive 等）——
                //     否则协程取消不生效，UI 卡死；
                // (b) 含 `break` / `return` —— CAS 重试循环（`_uiState.compareAndSet(...)` 直到成功）
                //     是同步有界循环，自己会退出，不需要挂起点。
                // 只看 delay 会误判 Compose 手势的 `awaitEachGesture { while(true) { awaitPointerEvent() } }`
                // （挂起点是 await*），也不看 break 会误判 CAS 重试。
                var depth = 0
                var suspends = false
                var selfExits = false
                for (j in index until lines.size) {
                    val text = lines[j]
                    if (SUSPEND_MARKERS.any { text.contains(it) }) suspends = true
                    if (j > index && (text.contains("break") || text.contains("return"))) selfExits = true
                    depth += text.count { it == '{' } - text.count { it == '}' }
                    if (j > index && depth <= 0) break
                }
                if (!suspends && !selfExits) {
                    violations += "$relativePath:$loopLine 的 while(true) 循环体既无挂起点也无退出语句——取消不再生效，UI 会卡死"
                }
            }
        }
        assertEquals(emptyList(), violations, "裸 while(true) 必须包在 LaunchedEffect 里且带 delay")
    }

    @Test
    fun `the gate sees the route hotspot so the loop rule is not vacuous`() {
        val route = File(appMain, "com/maodouchat/ui/screen/chatdetail/ChatDetailRoute.kt")
        assertTrue("ChatDetailRoute.kt 必须存在") { route.isFile }
        // G168b：剥注释——否则注释掉的 `while (true)` 会让这条自检「假通过」
        val text = stripComments(route.readText())
        assertTrue("该文件应当含 LaunchedEffect（否则上面的循环契约是空转）") {
            text.contains("LaunchedEffect")
        }
        assertTrue("该文件应当含 while (true)（已知的那处 UI 内落库）") {
            text.contains("while (true)")
        }
    }

    // ─── 4. UI 直连持久层的逐文件命中棘轮（G156b 从死门禁移植） ───

    /**
     * 直接摸持久层/全局单例的符号（G156b 从 `ClientHotspotRatchetTest` 移植）。
     *
     * **移植的原因**：那个门禁 3 条用例**全是红的**（基线冻结在 G63 的 5048/3131/2298，
     * 而热点早被拆到 3538/3102/2209；两个持久化基线还引用着已删除的
     * `SettingsSubScreens.kt` / `SettingsSubViewModels.kt`），而且它自己的 KDoc 承认
     * **CI 从不调用 `:core:testing:test`**——即这个门禁从来没有任何一次真正拦住过什么。
     *
     * **修掉的一处度量错误**：原门禁直接 `text.contains(symbol)` 数原始文本。
     * G184–G192 抽 dialog 时我给每个新文件写了同一条 KDoc「**拆解约束**：不抓任何全局单例、
     * 不读数据库、不 import `MaodouchatApp`」——**这句话本身含有 `MaodouchatApp`**。
     * 于是_raw_ 计数从「17 文件 / 84 处」虚增到「65 文件 / 129 处」：
     * 48 个文件纯粹因为「声明自己不碰单例」而被计成违规。
     * 现在改成在 [stripComments] 之后的源码上计数——真实值是
     * **20 文件 / 83 处**（`@Composable` 口径），与原门禁开工时基本持平。
     */
    private val directPersistenceSymbols = listOf("database.", "secretChatDao", "MaodouchatApp")

    /**
     * 剥掉行注释、块注释后的源码（字符串字面量里的双斜线 与 斜线星 不误剥）。
     *
     * 四态：0=代码 1=行注释 2=块注释 3=字符串 4=字符。
     */
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
     * G182d：**stripComments 自己的负控制，固化成常驻测试**。
     *
     * 源码文本门禁约定的第 2 条写着「剥注释本身要过负控制」，但此前它只是文档里的一句话——
     * 没有测试守着。于是将来注释驱逐逻辑回归（比如忘了处理字符串内的 `//`），
     * 没有人会知道，G215b 那种「门禁恒真」的陷阱会重演。
     *
     * 这里钉住两件事：
     * 1. 行注释 `//` 与块注释 `/* */` 里的内容必须被剥掉；
     * 2. **字符串字面量与字符字面量里的内容必须原样保留**（这是最容易写错的一处：
     *    把 `"http://x"` 的 `//` 当注释起点，会连坐吃掉后面的真代码）。
     */
    @Test
    fun `stripComments itself is under test`() {
        // 1) 行注释整行吃掉，代码留下
        val lineCommented = """
            val a = database foo() // bar baz
            val b = 2
        """.trimIndent()
        assertTrue(stripComments(lineCommented).contains("val a = database foo()"), "行注释内容必须被剥掉")
        assertTrue(!stripComments(lineCommented).contains("bar baz"), "行注释内容必须被剥掉")
        assertTrue(stripComments(lineCommented).contains("val b = 2"), "注释后的代码必须留下")

        // 2) 块注释跨行吃掉
        val blockCommented = """
            val a = 1 /* hidden
            still hidden */ val b = 2
        """.trimIndent()
        val strippedBlock = stripComments(blockCommented)
        assertTrue(!strippedBlock.contains("hidden"), "块注释内容必须被剥掉")
        assertTrue(strippedBlock.contains("val a = 1"), "块注释前的代码必须留下")
        assertTrue(strippedBlock.contains("val b = 2"), "块注释后的代码必须留下")

        // 3) 字符串字面量里的 // 和 /* 不是注释起点（剥完必须原样在）
        val inString = """val url = "http://example.com/a/*b"; val n = 7"""
        val strippedString = stripComments(inString)
        assertTrue(
            strippedString.contains("http://example.com/a/*b"),
            "字符串里的 // 与 /* 不得被当注释起点——否则会连坐吃掉后面的真代码",
        )
        assertTrue(strippedString.contains("val n = 7"), "字符串之后的代码必须留下")

        // 4) 字符字面量同样受保护
        assertTrue(stripComments("val c = '/'").contains("'/'"), "字符字面量不得被误判为注释")
    }

    // G182d：三个定界符一律用拼接构造，源文本里不出现字面实例（这条门禁自己也受管辖）
    private val slashStar = "/" + "*"
    private val starSlash = "*" + "/"
    private val slashSlash = "/" + "/"

    /**
     * 抽出「紧邻 @Test 函数的 KDoc」的 (行号, 正文)。正文不含首尾定界符。
     *
     * 开口必须**位于行首**（前面只有空白）才算 KDoc——写在代码字符串里的同名片段不算。
     *
     * G182d 踩过两次，都是字面量拼错的：
     * (a) 一版把开口当成「两个斜线星拼接」，真值是四字符的斜线星加斜线星；
     *     而 KDoc 开口是斜线星星（三字符）——indexOf 与正则自然都匹配不上，
     *     门禁空转，被下面的防空转断言抓住；
     * (b) 更讽刺的是，本想把 (a) 写进这段 KDoc 说明，结果写的时候贴了那两个符号的
     *     字面实例，其中星斜线**真的把这段 KDoc 提前结束**，报 Unclosed comment。
     *     这正是这条门禁要防的事——所以按同一约定，这里只用文字。
     */
    private fun kdocBodiesOfTestFunctions(text: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        val docOpen = slashStar + "*"   // 斜线星星（三字符）；两个斜线星拼接会得到四字符，匹配不上
        var from = 0
        while (true) {
            val start = text.indexOf(docOpen, from)
            if (start < 0) break
            from = start + 1
            val lineStart = text.lastIndexOf('\n', if (start == 0) 0 else start - 1) + 1
            val prefix = text.substring(lineStart, start)
            if (prefix.isNotEmpty() && !prefix.all { it == ' ' || it == '\t' }) continue
            val bodyStart = start + docOpen.length
            val end = text.indexOf(starSlash, bodyStart)
            if (end < 0) continue
            val body = text.substring(bodyStart, end)
            val after = text.substring(end + 2).take(600).trimStart()
            val annotations = Regex("^(@\\w+(\\([^)]*\\))?[ \\t]*\n?[ \\t]*)*").find(after)?.value ?: ""
            val rest = after.removePrefix(annotations).trimStart()
            if (rest.startsWith("fun ") && after.contains("@Test")) {
                out.add((text.substring(0, start).count { it == '\n' } + 1) to body)
            }
        }
        return out
    }

    /**
     * G182d：**任何 @Test 函数的 KDoc 里不得出现注释定界符的字面实例**。
     *
     * 这是源码文本门禁约定第 3 条的固化。
     *
     * 实测（G182d）：Kotlin 的块注释是**可嵌套**的，所以 KDoc 里出现斜线星会**再开一层**
     * 注释，出现星斜线会提前结束——两者都会**编译失败**，由编译器强制，
     * 测试门禁抓不到（我试过，两次都是 compileDebugUnitTestKotlin FAILED）。
     * 因此这条门禁真正能守的只有**双斜线**：它能编译通过，却会污染任何
     * 「按出现次数判罚」的粗粒度门禁——G156b 正是这么虚增 55% 的。
     *
     * 按同一约定，文中提这三个符号时用文字（斜线星 / 星斜线 / 双斜线）或拼接。
     * **本文件自己也在这条规则管辖之内**：上面这段 KDoc 和下面的常量都靠字符串
     * 拼接构造，源文本里没有任何字面实例——所以不需要给门禁自己开豁免口。
     *
     * 协议分隔符 `://` 不算违规（KDoc 里写 URL 是正常需求），其余一律算。
     */
    @Test
    fun `no test kdoc contains a literal comment delimiter`() {
        val testRoot = File(repoRoot, "app/src/test")
        assertTrue(testRoot.isDirectory, "app/src/test 不存在——空目录会让这条门禁恒真")
        var scanned = 0
        val violations = mutableListOf<String>()
        testRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { f ->
                kdocBodiesOfTestFunctions(f.readText()).forEach { (line, body) ->
                    scanned++
                    // 协议分隔符不算：先摘掉 ://，再查双斜线
                    val withoutProtocol = body.replace("://", "")
                    val found = mutableListOf<String>()
                    if (body.contains(slashStar)) found.add("斜线星")
                    if (body.contains(starSlash)) found.add("星斜线")
                    if (withoutProtocol.contains(slashSlash)) found.add("双斜线")
                    if (found.isNotEmpty()) {
                        violations += "${f.relativeTo(repoRoot).path}:$line 出现 ${found.joinToString("、")}"
                    }
                }
            }
        assertTrue(
            scanned >= 20,
            "只抽到 $scanned 个带 KDoc 的 @Test——扫描逻辑可能坏了，这条门禁正在空转（G215b 同族陷阱）",
        )
        assertTrue(
            violations.isEmpty(),
            "下列 @Test 的 KDoc 含注释定界符字面实例。按约定改成文字（斜线星/星斜线/双斜线）或拼接：\n" +
                violations.joinToString("\n"),
        )
    }

    /** 逐文件统计直连持久层命中数；[onlyComposableFiles] 为真时只数含 `@Composable` 的文件。 */
    private fun directPersistenceHits(onlyComposableFiles: Boolean): Map<String, Int> {
        val uiRoot = File(appMain, "com/maodouchat/ui")
        return uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(uiRoot).path.replace('\\', '/') to stripComments(it.readText()) }
            .filter { (_, text) -> !onlyComposableFiles || text.contains("@Composable") }
            .map { (path, text) ->
                path to directPersistenceSymbols.sumOf { Regex(Regex.escape(it)).findAll(text).count() }
            }
            .filter { (_, hits) -> hits > 0 }
            .toMap()
            .toSortedMap()
    }

    /** U02 主口径：含 `@Composable` 的文件。只许下降，变多即红。 */
    private val frozenComposableDirectPersistence: Map<String, Int> = mapOf(
        "navigation/CallNavigation.kt" to 9,
        "navigation/MainContainerRoute.kt" to 6,
        "navigation/NavGraph.kt" to 4,
        "screen/call/CallHistoryScreen.kt" to 4,
        "screen/chatdetail/AiTasksScreen.kt" to 9,
        "screen/chatdetail/ChatDetailRoute.kt" to 2,
        "screen/chatdetail/MediaCenterScreen.kt" to 6,
        "screen/chatdetail/StarredMessagesScreen.kt" to 6,
        "screen/chatlist/GlobalSearchScreen.kt" to 8,
        "screen/chatlist/NotificationCenterScreen.kt" to 2,
        "screen/contacts/ContactSubScreens.kt" to 4,
        "screen/groupplay/GroupChainScreen.kt" to 2,
        "screen/groupplay/GroupCheckinScreen.kt" to 2,
        "screen/groupplay/GroupPkScreen.kt" to 2,
        "screen/groupplay/GroupPollScreen.kt" to 2,
        "screen/settings/SettingsAccountSecurity.kt" to 1,
        "screen/settings/SettingsAccountSecurityScreen.kt" to 3,
        "screen/settings/SettingsAiPrivacy.kt" to 5,
        "screen/settings/SettingsServer.kt" to 5,
        "theme/Motion.kt" to 1,
    )

    /** 次口径：整个 `ui/`（含 ViewModel / Ports）。同样只许下降。 */
    private val frozenUiDirectPersistence: Map<String, Int> = mapOf(
        "navigation/AuthDestinations.kt" to 2,
        "navigation/CallNavigation.kt" to 9,
        "navigation/MainContainerRoute.kt" to 6,
        "navigation/NavGraph.kt" to 4,
        "navigation/SearchCenterDestinations.kt" to 5,
        "screen/call/CallHistoryScreen.kt" to 4,
        "screen/call/CallViewModel.kt" to 3,
        "screen/chatdetail/AiTasksScreen.kt" to 9,
        "screen/chatdetail/ChatDetailAiIntents.kt" to 2,
        "screen/chatdetail/ChatDetailAiResults.kt" to 1,
        "screen/chatdetail/ChatDetailDisappearing.kt" to 3,
        "screen/chatdetail/ChatDetailLiveLocation.kt" to 2,
        "screen/chatdetail/ChatDetailMedia.kt" to 1,
        "screen/chatdetail/ChatDetailRoute.kt" to 2,
        "screen/chatdetail/ChatDetailViewModel.kt" to 36,
        "screen/chatdetail/ChatExportController.kt" to 2,
        "screen/chatdetail/GroupDetailViewModel.kt" to 2,
        "screen/chatdetail/MediaCenterScreen.kt" to 6,
        "screen/chatdetail/StarredMessagesScreen.kt" to 6,
        "screen/chatlist/ChatListPorts.kt" to 25,
        "screen/chatlist/ChatListRealtimeCoordinator.kt" to 3,
        "screen/chatlist/ChatListUiState.kt" to 1,
        "screen/chatlist/GlobalSearchScreen.kt" to 8,
        "screen/chatlist/NotificationCenterScreen.kt" to 2,
        "screen/contacts/ContactSubScreens.kt" to 4,
        "screen/contacts/ContactsRepository.kt" to 3,
        "screen/contacts/ContactsViewModel.kt" to 10,
        "screen/explore/ExploreViewModel.kt" to 1,
        "screen/groupplay/GroupChainScreen.kt" to 2,
        "screen/groupplay/GroupCheckinScreen.kt" to 2,
        "screen/groupplay/GroupPkScreen.kt" to 2,
        "screen/groupplay/GroupPollScreen.kt" to 2,
        "screen/login/LoginViewModel.kt" to 2,
        "screen/settings/SettingsAccountSecurity.kt" to 1,
        "screen/settings/SettingsAccountSecurityScreen.kt" to 3,
        "screen/settings/SettingsAiPrivacy.kt" to 5,
        "screen/settings/SettingsGeneralSettingsViewModel.kt" to 1,
        "screen/settings/SettingsNotificationViewModel.kt" to 3,
        "screen/settings/SettingsServer.kt" to 5,
        "screen/settings/SettingsViewModel.kt" to 2,
        "theme/Motion.kt" to 1,
    )

    @Test
    fun `composable files do not reach into the database directly`() {
        assertEquals(
            frozenComposableDirectPersistence,
            directPersistenceHits(onlyComposableFiles = true),
            "Composable 直连持久层/全局单例的命中数变了。变多说明又绕过了 effect handler（U02 未达标）；" +
                "变少是好消息，请把 frozenComposableDirectPersistence 改小。",
        )
    }

    @Test
    fun `the whole ui layer keeps its direct persistence budget`() {
        assertEquals(
            frozenUiDirectPersistence,
            directPersistenceHits(onlyComposableFiles = false),
            "ui/ 直连持久层/全局单例的总命中数变了。变多请先问是不是又绕过了 ports/effect handler；" +
                "变少是好消息，请把 frozenUiDirectPersistence 改小。",
        )
    }

    // ─── 5. 全仓库测试态势（G157b） ───

    /**
     * 当前**有测试源文件**的模块（G157b 实测：21 个模块里只有这 5 个）。
     *
     * 冻结它有两个作用：
     * - 新建模块却没写测试 → 逼人确认「这个模块该不该有测试」是个决定，不是默认没有；
     * - 删掉某个模块的测试 → 必须同步改这里，让「测试在消失」至少有一次显式确认。
     */
    private val modulesWithTests: Set<String> = setOf(
        "core/crypto",
        "core/realtime",
        "core/session",
        "core/testing",
        "domain/messaging",
    )

    @Test
    fun `the set of modules that own tests only changes deliberately`() {
        val actual = listOf("core", "domain", "feature")
            .map { File(repoRoot, it) }
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isDirectory && it.name == "test" }
                    .filter { testDir -> testDir.walkTopDown().any { it.isFile && it.extension == "kt" } }
                    // src/test 的上级是 src，再上一级才是模块目录
                    .map { testDir ->
                        testDir.parentFile.parentFile.toRelativeString(repoRoot).replace('\\', '/')
                    }
                    .toList()
            }
            .toSortedSet()
        assertEquals(
            modulesWithTests.sorted(),
            actual.toList(),
            "有测试源文件的模块集合变了。新增模块若没有测试，请确认这是有意的；" +
                "删掉测试则同步改小 modulesWithTests。",
        )
    }

    @Test
    fun `the orphan gate module still owns a real test`() {
        val coreTesting = File(repoRoot, "core/testing/src/test")
        assertTrue(coreTesting.isDirectory, "core/testing/src/test 不存在")
        // 必须剥注释：否则把 `// @ArchTest` 注释掉也算「还有测试」——
        // 这正是 G156b（持久化计数）、G155b（文档追溯门禁）踩过的同一个坑的第三次。
        val texts = ktFilesUnder(coreTesting).map { stripComments(it.readText()) }
        assertTrue(texts.isNotEmpty(), "core/testing/src/test 下没有任何 .kt——孤儿模块的测试被删光了")
        // 注意：ArchUnit 规则用的是 @ArchTest（挂在 val 上），不是 @Test。
        // 只认 @Test 会把这个模块唯一的测试误判成空壳。
        assertTrue(
            texts.any { it.contains("@Test") || it.contains("@ArchTest") },
            "core/testing/src/test 下没有任何 @Test/@ArchTest——又一个不会被 CI 调用的空壳门禁",
        )
    }

    /**
     * G158b：[stripComments] 的自检——「源码文本判决第一步必须剥注释」这一工程约定的「可执行」部分。
     *
     * 为什么要单测它：本项目四套源码文本门禁里有三套都因为「不剥注释」而误判过
     * （G155b/G156b/G157b）。`stripComments` 现在是这些门禁的共同地基，
     * 它一旦被改坏，**所有依赖它的门禁会一起静默通过**——那比没有门禁更糟。
     */
    @Test
    fun `stripComments really strips comments and never eats string literals`() {
        val fixture = """
            val a = "MaodouchatApp"      // 字符串字面量里的符号必须保留
            // val b = MaodouchatApp     ← 行注释里的必须消失
            /* val c = MaodouchatApp */  ← 块注释里的必须消失
            val d = MaodouchatApp        ← 真实代码里的必须保留
            val e = "http://x/*.y"       ← 字符串里的双斜线不是注释
        """.trimIndent()

        val code = stripComments(fixture)

        // 真实代码与字符串字面量各保留一处
        assertEquals(2, Regex("MaodouchatApp").findAll(code).count(), "字符串字面量与真实代码里的符号都应保留")
        // 注释里的两处必须消失
        assertFalse(code.contains("val b ="), "行注释没被剥掉")
        assertFalse(code.contains("val c ="), "块注释没被剥掉")
        // 字符串字面量里的双斜线 / 星 不能触发注释
        assertTrue(code.contains("http://x/*.y"), "字符串字面量被误剥")
        // 注释本体必须消失。注意不能 blanket 断言「不含 斜线星」——
        // fixture 的字符串字面量里故意放了 `http://x/*.y`，那是**代码**，必须保留。
        // （第一版就在这里写错： blanket 断言把自己 fixture 里的合法内容判成残留。）
        // 上面两条已足以证明注释被剥掉。不要在加第三条「注释正文也消失」——
        // fixture 第三行 `*/ ← 块注释里的必须消失` 里，`*/` 之后的部分**是代码**，
        // 解析器保留它是正确的（我第一版就这么误判了一次）。
        assertFalse(code.contains("// val b"), "行注释残留")
        assertFalse(code.contains("/* val c"), "块注释残留")
    }

    /**
     * G169b：同一构建内 `stripComments` 的多份拷贝必须**文本一致**。
     *
     * 它是拷贝-粘贴出来的助手函数，已经有 4 份、漂成过 3 个变体
     * （`MessagingInvariantTraceabilityTest` 里 `var i` 声明在 `var state` 之前）。
     * 漂移目前只是美观问题，但真正危险的是将来有人「优化」其中一份
     * ——比如动了字符串状态的处理——于是四套门禁对同一段代码给出**不同判决**，
     * 而没有任何东西会报警。
     *
     * `server/` 是独立 Gradle 构建，跨构建无法共享实现，所以 server 侧有它自己
     * 的一条等价门禁；**两侧之间靠人工同步**——改任一份都要改另一份。
     */
    /**
     * G182g：**「哪套门禁受管辖」必须与事实一致**。
     *
     * 该约定开头原本写「本项目有四套读源码文本下结论的门禁」，把
     * `core/testing/ArchitectureTest` 也列进去了。**实测（G182g）它是 ArchUnit 的
     * `@AnalyzeClasses`，读的是编译后的字节码，不是源码文本**——注释在字节码里
     * 根本不存在，所以它对那三条规则天然免疫。
     *
     * 真正受管辖的是 **3 套**：
     *   1. `app/src/test/.../ClientArchitectureTest.kt`
     *   2. `server/src/test/.../architecture/ServerArchitectureTest.kt`
     *   3. `server/src/test/.../messaging/MessagingInvariantTraceabilityTest.kt`
     *
     * 这条测试冻结这个集合：**将来新增第四套源码文本门禁时必须同步更新这个集合**，
     * 否则这里红——避免「以为有四套、实际五套」那种文档与事实漂移。
     *
     * 注意：这是**枚举而非特征识别**。用特征（比如「含 stripComments」）自动发现
     * 新门禁听起来更美，但不含 stripComments 的门禁恰是违反第 1 条的——
     * 特征识别会系统性地漏掉最该抓的那些。宁可穷举 + 冻结。
     */
    @Test
    fun `the source-text gate inventory is pinned`() {
        val expected = setOf(
            "app/src/test/java/com/maodouchat/ClientArchitectureTest.kt",
            "server/src/test/kotlin/com/maodouchat/server/architecture/ServerArchitectureTest.kt",
            "server/src/test/kotlin/com/maodouchat/server/messaging/MessagingInvariantTraceabilityTest.kt",
            // 第五份拷贝，本身不是门禁（G167b/G216b 的零引用棘轮），但受 copy-consistency 管辖
            "app/src/test/java/com/maodouchat/util/GroupPlayPolicyTest.kt",
        )
        val actual = mutableSetOf<String>()
        // app 侧与 server 侧分两个根扫——server/ 是独立 Gradle 构建
        for (root in listOf(File(repoRoot, "app/src/test"), File(repoRoot, "server/src/test"))) {
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.readText().contains("private fun stripComments(") }
                .forEach { actual.add(it.relativeTo(repoRoot).path.replace('\\', '/')) }
        }
        // 冻结的是「所有带 stripComments 的文件」而不只是那三套门禁：
        // GroupPlayPolicyTest 里也有第五份拷贝（G167b/G216b 的零引用棘轮复用它），
        // 它自己不是门禁，但那份拷贝同样受 copy-consistency 管辖
        // （app 侧的同一测试扫整个 app/src/test）。
        // 所以这里冻住完整的 4 个文件——将来无论新增门禁还是新增复用方，
        // 都必须同步更新本测试的 expected 清单。
        assertEquals(
            expected,
            actual,
            "带 stripComments 的文件集合变了。若新增了一套源码文本门禁或新的复用方，" +
                "请同步更新本测试的 expected 门禁清单。",
        )
        expected.forEach { path ->
            val f = File(repoRoot, path)
            assertTrue(f.isFile, "清单里的门禁不存在了：$path")
            assertTrue(
                f.readText().contains("readText()"),
                "$path 不读源码文本——它不该在管辖清单里",
            )
        }
    }

    @Test
    fun `every copy of stripComments in this build is textually identical`() {
        val copies = ktFilesUnder(File(repoRoot, "app/src/test"))
            .filter { it.readText().contains("private fun stripComments(") }
            .map { it.relativeTo(repoRoot).path.replace('\\', '/') to extractStripComments(it.readText()) }
            .toMap()
        assertTrue(copies.size >= 2, "app 侧应该至少有 2 份 stripComments，实际 ${copies.size} 份")
        val distinct = copies.values.distinct()
        assertEquals(
            1,
            distinct.size,
            "stripComments 的拷贝漂移了——它们必须逐字相同：" +
                copies.entries.joinToString("\n") { "${it.key}: ${if (it.value == distinct.first()) "相同" else "**不同**"}" },
        )
    }

    /** 从源文件里切出 `stripComments` 函数体（用于比对多份拷贝是否一致）。 */
    private fun extractStripComments(source: String): String {
        val start = source.indexOf("private fun stripComments(")
        assertTrue("文件里没有 stripComments") { start >= 0 }
        // 从函数签名的行首开始，到第一个单独一行的 `    }` 结束
        val lineStart = source.lastIndexOf('\n', start) + 1
        val end = source.indexOf("\n    }\n", start)
        assertTrue("找不到 stripComments 的结束") { end >= 0 }
        return source.substring(lineStart, end + 6).trim()
    }

    @Test
    fun `the release endpoint guard is still in the build file`() {
        val text = File(repoRoot, "app/build.gradle.kts").readText()
        assertTrue(
            text.contains("MAODOU_RELEASE_API_BASE_URL"),
            "app/build.gradle.kts 里没有 MAODOU_RELEASE_API_BASE_URL——" +
                "发布端点护栏被删了，Release 会静默回落到 https://invalid.maodouchat.local",
        )
        assertTrue(
            text.contains("Release API_BASE_URL must be set"),
            "发布端点护栏的 require 消息不见了——护栏可能被改成「留空也放行」",
        )
    }
}
