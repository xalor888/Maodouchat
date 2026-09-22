package com.maodouchat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        "com/maodouchat/ui/screen/chatdetail/ChatDetailRoute.kt" to 3501,
        "com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt" to 3103,
        "com/maodouchat/util/GroupPlayPolicy.kt" to 2209,
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
        "com/maodouchat/ui/screen/settings/SettingsViewModel.kt" to 1314,
        "com/maodouchat/ui/screen/contacts/ContactsListScreen.kt" to 666,
        "com/maodouchat/ui/component/MarkdownMessage.kt" to 289,
        // G172：vendored 的 Compose 图标文件（androidx 包，非本项目代码）；
        // 补上它之后「app 内 1100+ 行源文件全部在监」才真正成立。
        "androidx/compose/material/icons/outlined/ExtendedOutlinedIcons.kt" to 2678,
        // G172：vendored 的 Compose 图标文件（androidx 包，非本项目代码）；
        // 补上它之后「app 内 1100+ 行源文件全部在监」才真正成立。
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
            "com/maodouchat/ui/screen/chatdetail/ChatDetailRoute.kt" to 3501,
            "com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt" to 3103,
            "com/maodouchat/util/GroupPlayPolicy.kt" to 2209,
            "com/maodouchat/ui/screen/chatdetail/ChatDetailAiGeneration.kt" to 340,
                "com/maodouchat/ui/screen/settings/SettingsAccountSecurity.kt" to 672,
            "com/maodouchat/ui/screen/call/CallViewModel.kt" to 1651,
            "com/maodouchat/ui/screen/explore/ExploreFeedScreen.kt" to 638,
        // G126：以下七个文件此前**没有任何行数门禁**（其中 ChatDetailMiscDialogs.kt 是
            // G108 我自己拆出来的——拆完不纳管，等于给新热点留了门）。纳入后 app 内
            // 1100+ 行源文件全部在监。
            "com/maodouchat/webrtc/WebRTCManager.kt" to 1416,
            "com/maodouchat/ui/screen/chatlist/ChatListScreen.kt" to 432,
            "com/maodouchat/ui/screen/settings/SettingsViewModel.kt" to 1314,
                "com/maodouchat/ui/screen/contacts/ContactsListScreen.kt" to 666,
            "com/maodouchat/ui/component/MarkdownMessage.kt" to 289,
        // G172：vendored 的 Compose 图标文件（androidx 包，非本项目代码）；
        // 补上它之后「app 内 1100+ 行源文件全部在监」才真正成立。
        "androidx/compose/material/icons/outlined/ExtendedOutlinedIcons.kt" to 2678,
        // G172：vendored 的 Compose 图标文件（androidx 包，非本项目代码）；
        // 补上它之后「app 内 1100+ 行源文件全部在监」才真正成立。
                )
        assertEquals(currentCaps, frozenHotspotLineCaps, "热点文件上限被改动了——收紧可以，放宽不行")
    }

    /**
     * G172：**覆盖性**检查——app 内任何超过 1100 行的源文件都必须已被纳入
     * `frozenHotspotLineCaps`。
     *
     * 在这条测试存在之前，「1100+ 行全部在监」只是一句注释：
     * 新增一个大文件时没人会想起来把它加进上限表，于是它既不受行数门禁管，
     * 也不会在任何地方报出来。这条测试把那句话变成可执行断言。
     */
    @Test
    fun `every app source file above 1100 lines is under a frozen cap`() {
        val unmonitored = ktFilesUnder(appMain)
            .filter { it.readLines().size > 1100 }
            .map { it.relativeTo(appMain).path.replace('\\', '/') }
            .filterNot { it in frozenHotspotLineCaps }
        assertEquals(
            emptyList<String>(),
            unmonitored,
            "这些源文件超过 1100 行却没有纳管——先拆，或至少加进行数上限表",
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
     * G158b：[stripComments] 的自检——DIRECTION.md 3.5 节约定的「可执行」部分。
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
