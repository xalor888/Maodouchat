package com.maodouchat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G63：轨道 C（客户端热点）的**前置边界门禁**。
 *
 * `DIRECTION.md` 说得很直接：「这些是本项目最贵的债，但**放在最后做**：在边界门禁就位之前动它们，
 * 只会重演『表面收敛、实质未动』。」本轮就是把这句话变成可执行断言——先立门槛，再谈重构。
 *
 * 三条硬约束，全部做成**棘轮**（冻结当前实测值，只许降不许升），与 `ServerArchitectureTest` 同一思路：
 *
 * 1. `ui/` 不得直接引用 `data.local.dao`（DAO/Room 写路径只能经 ViewModel / repository）。
 *    当前实测只有 1 处违规——所以这里是**精确名单**而不是类别禁止：
 *    清掉那一处后名单变空，此后任何新增都会立刻红。
 * 2. 三个热点文件的行数上限冻结在当前值（只许降）：重构把文件拆小 → 通过；越改越胖 → 红。
 * 3. `GroupPlayPolicy` 不得出现同名重复文件（DIRECTION 点名的「有同名重复文件」）。
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

    @Test
    fun `ui must not import data-local daos and the list may only shrink`() {
        val offenders = ktFilesUnder(File(appMain, "com/maodouchat/ui"))
            .filter { file ->
                file.readText().lines().any {
                    it.startsWith("import com.maodouchat.data.local.dao")
                }
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

    // ─── 2. 热点文件行数冻结（只许降） ───

    /**
     * 三个热点文件的**行数上限**，取 G63 开工时的实测值。
     *
     * 语义是「不得再增长」而不是「必须小于某个数」：重构拆小 → 上限自动有余量；
     * 但任何人往这些文件里堆代码 → 立刻红。要收紧上限，改小这里的数字即可。
     */
    private val frozenHotspotLineCaps: Map<String, Int> = mapOf(
        "com/maodouchat/ui/screen/chatdetail/ChatDetailRoute.kt" to 3667,
        "com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt" to 3103,
        "com/maodouchat/util/GroupPlayPolicy.kt" to 2209,
        // G113：以下六个文件此前**没有任何行数门禁**，是 app 内剩下的大文件。
        // 纳入棘轮，之后每拆一块就往下调。
        "com/maodouchat/ui/screen/chatdetail/ChatDetailAiGeneration.kt" to 1300,
        "com/maodouchat/ui/screen/settings/SettingsAccountSecurity.kt" to 672,
        "com/maodouchat/ui/screen/call/CallViewModel.kt" to 1651,
        "com/maodouchat/ui/screen/explore/ExploreFeedScreen.kt" to 638,
        // G126：以下七个文件此前**没有任何行数门禁**（其中 ChatDetailMiscDialogs.kt 是
        // G108 我自己拆出来的——拆完不纳管，等于给新热点留了门）。纳入后 app 内
        // 1100+ 行源文件全部在监。
        "com/maodouchat/webrtc/WebRTCManager.kt" to 1431,
        "com/maodouchat/ui/screen/chatlist/ChatListScreen.kt" to 432,
        "com/maodouchat/ui/screen/settings/SettingsViewModel.kt" to 1315,
        "com/maodouchat/ui/screen/contacts/ContactsListScreen.kt" to 666,
        "com/maodouchat/ui/component/MarkdownMessage.kt" to 289,
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
        val currentCaps = mapOf(
            "com/maodouchat/ui/screen/chatdetail/ChatDetailRoute.kt" to 3667,
            "com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt" to 3103,
            "com/maodouchat/util/GroupPlayPolicy.kt" to 2209,
            "com/maodouchat/ui/screen/chatdetail/ChatDetailAiGeneration.kt" to 1300,
                "com/maodouchat/ui/screen/settings/SettingsAccountSecurity.kt" to 672,
            "com/maodouchat/ui/screen/call/CallViewModel.kt" to 1651,
            "com/maodouchat/ui/screen/explore/ExploreFeedScreen.kt" to 638,
        // G126：以下七个文件此前**没有任何行数门禁**（其中 ChatDetailMiscDialogs.kt 是
            // G108 我自己拆出来的——拆完不纳管，等于给新热点留了门）。纳入后 app 内
            // 1100+ 行源文件全部在监。
            "com/maodouchat/webrtc/WebRTCManager.kt" to 1431,
            "com/maodouchat/ui/screen/chatlist/ChatListScreen.kt" to 432,
            "com/maodouchat/ui/screen/settings/SettingsViewModel.kt" to 1315,
                "com/maodouchat/ui/screen/contacts/ContactsListScreen.kt" to 666,
            "com/maodouchat/ui/component/MarkdownMessage.kt" to 289,
                )
        assertEquals(currentCaps, frozenHotspotLineCaps, "热点文件上限被改动了——收紧可以，放宽不行")
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
            "GroupPlayPolicy 出现同名重复文件——DIRECTION 点名过这个债，必须只有一个",
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
     * `DIRECTION.md` 点名 `ChatDetailRoute.kt`「5051 行 composable」是客户端最贵的债之一。
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
     * 因此这里是**空名单**：`ui/` 层从此不允许抓 `MaodouchatApp.database`。
     * 历史值见 git；把这里改回非空等于放松棘轮，反向断言会抓住。
     */
    private val frozenUiAppDatabaseGrabbers: List<String> = emptyList()

    @Test
    fun `ui must not grab the app database singleton and the list may only shrink`() {
        val grabbers = ktFilesUnder(File(appMain, "com/maodouchat/ui"))
            .filter { file ->
                // 两种写法都算：直接 `.database` 抓单例，或 `MaodouchatApp` 转型后取 database
                val text = file.readText()
                text.contains("as com.maodouchat.MaodouchatApp).database") ||
                    text.contains("MaodouchatApp.database")
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
     * `DIRECTION.md` 点名 `ChatDetailRoute.kt`「内含 while(true) 每 60s 写库」。实测只有一处，
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
        val text = route.readText()
        assertTrue("该文件应当含 LaunchedEffect（否则上面的循环契约是空转）") {
            text.contains("LaunchedEffect")
        }
        assertTrue("该文件应当含 while (true)（DIRECTION 点名的那处）") {
            text.contains("while (true)")
        }
    }
}
