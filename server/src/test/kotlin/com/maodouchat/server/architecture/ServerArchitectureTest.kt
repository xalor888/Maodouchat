package com.maodouchat.server.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M1：服务端架构棘轮门禁。
 *
 * 背景：`docs/full-project-refactor-checklist.md` 把「repository=SQL 边界、service=领域门面、
 * route→service→repository」写成中心契约，但实测 `plugins/` 里曾躺着 75 处 handler 内直写事务、
 * 36 个 plugin 文件直接 import Exposed，另有 `repository/`→`plugins/` 反向依赖。
 * 只写在文档里的契约不是契约——这份测试把它变成**会失败的东西**。
 * （G292b 更新：那 36 个 import 已随 G249b–G262b 的工作全部删净，
 *  `plugins/` 下 import Exposed 的文件数现为 **0**，见 `frozenPluginsImportingExposed`。）
 *
 * 两类规则：
 *
 * 1. **绝对不变量（absolute）**：今天已经是 0 违规、且没有正当理由违反的依赖方向。
 *    一旦违反立即红，没有基线可调。
 *
 * 2. **棘轮（ratchet）**：今天确实存在的历史债。基线**精确冻结**在下面的常量里，
 *    断言 `actual == baseline`：
 *      - 违规变多 → 红（不许新增）。
 *      - 违规变少 → 也红，但这是**好消息**：把对应常量改小即可（这是预期工作流）。
 *    之所以用相等而不是 `<=`，是因为本项目的历史教训是「乐观叙述」：`[x]` 曾经写进了
 *    文档却没有证据。精确相等保证这份基线数字永远等于代码的真实状态，不会悄悄失真。
 *
 * 度量口径是**文本扫描**（与 `grep -roE` 对齐，便于人工复核），不做 AST 解析；
 * 宁可多报（如注释里的引用），不可漏报。
 */
class ServerArchitectureTest {

    // ------------------------------------------------------------------
    // 棘轮基线（当前实测值，见 DIRECTION.md 第 0 节）
    // ------------------------------------------------------------------

    /**
     * `plugins/` 下每个文件的 `transaction {` 出现次数。
     *
     * G46–G57 逐轮下沉后已归零（最后一个是 `DeveloperRouting.kt` 的 4 处）。
     * 这里保留**空 map + 棘轮断言**：今后任何路由层新开事务都会立刻红。
     */
    private val frozenRouteTransactions: Map<String, Int> = emptyMap()

    /**
     * `plugins/` 下直接 import Exposed（`org.jetbrains.exposed`）的文件。
     *
     * G292b：**基线从 17 个文件下调为 0**。这 17 个文件各带 1-5 行 Exposed import，
     * 经 G249b–G262b 十四个轮次证明**全部零使用**（结构证：枚举全部 60 个 Table 对象，
     * plugins/ 内无任何 Table 接收者；具名证：eq/selectAll/andWhere/ResultRow/
     * SqlExpressionBuilder/transaction 全层零引用；连带证：plugins/ 内无通配 import
     * 依赖兄弟文件），已全部删净。
     *
     * 因此本棘轮的方向也随之反转：此前是「不许新增」，现在是「**必须保持为 0**」——
     * 任何新增的 Exposed import 都会让 actual 非空而立刻报红。
     *
     * 注：`StatusPages.kt` 仍会有 `exception<...ExposedSQLException>` 的全限定名引用，
     * 但那不是 import 行，不计入本判据（G218b 的口径）。
     */
    private val frozenPluginsImportingExposed: Set<String> = emptySet()

    /**
     * `repository/` 反向依赖 `plugins/` 的**引用处数**（非仅文件数）。已归零 (0 处 / 0 个文件)。
     */
    private val frozenRepositoryDependingOnPlugins: Map<String, Int> = emptyMap()

    /**
     * `service/` 反向依赖 `plugins/` 的**引用处数**。已归零 (0 处 / 0 个文件)。
     */
    private val frozenServicesDependingOnPlugins: Map<String, Int> = emptyMap()

    /** 物理错放在 `repository/` 的 `*Service.kt`。当前 16 个（应迁往 `service/`）。 */
    private val frozenServicesInRepositoryPackage: Set<String> = setOf(
        "AccountLifecycleService.kt",
        "AttachmentCommitService.kt",
        "BlockService.kt",
        "ConversationCommandService.kt",
        "ConversationCreationService.kt",
        "CredentialService.kt",
        "FeedQueryService.kt",
        "GroupInvitationService.kt",
        "GroupMembershipService.kt",
        "MediaReferenceService.kt",
        "PostCommandService.kt",
        "PostInteractionService.kt",
        "PrivacyService.kt",
        "ProfileService.kt",
        "SocialGraphService.kt",
        "UploadSessionService.kt",
    )

    // ------------------------------------------------------------------
    // 源码文本判决的公共前置：剥注释（G168b）
    // ------------------------------------------------------------------

    /**
     * 剥掉行注释与块注释后的源码（字符串字面量里的双斜线 与 斜线星 不误剥）。
     *
     * 本文件此前**六处判决全是裸 `readText()`**——注释掉的 `transaction {`、注释掉的
     * `import ...plugins` 都被当成真实违规计入棘轮。方向是「虚高」，于是会逼人去
     * 「修」一个不存在的问题；临时注释掉代码也会让门禁红。
     *
     * 这是同一个坑的第四次（G155b / G156b / G157b / G168b），
     * DIRECTION.md 3.5 已把它写成工程约定。`server/` 是独立 Gradle 构建，
     * 暂时不复用 `MessagingInvariantTraceabilityTest` 里那份实现，
     * 但两份语义必须保持一致。
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

    /** 读源文件并剥注释——本文件所有源码文本判决都应走这里。 */
    private fun File.codeText(): String = stripComments(readText())

    // ------------------------------------------------------------------
    // 绝对不变量
    // ------------------------------------------------------------------

    @Test
    fun `messaging must not depend on route layer plugins`() {
        val offenders = filesUnder("messaging")
            .filter { it.codeText().contains("com.maodouchat.server.plugins") }
            .map { it.name }
        assertEquals(
            emptyList(),
            offenders,
            "messaging/ 是消息领域层，不得依赖 plugins/（route/装配层）。" +
                "需要复用 route 层 helper 时，把 helper 下沉到中立包。",
        )
    }

    @Test
    fun `model must not depend on db repository service or plugins`() {
        val forbidden = listOf(
            "org.jetbrains.exposed" to "Exposed DSL",
            "com.maodouchat.server.db" to "db",
            "com.maodouchat.server.repository" to "repository",
            "com.maodouchat.server.service" to "service",
            "com.maodouchat.server.plugins" to "plugins",
        )
        val offenders = mutableListOf<String>()
        filesUnder("model").forEach { file ->
            val text = file.codeText()
            forbidden.forEach { (needle, label) ->
                if (text.contains(needle)) offenders += "${file.name} -> $label"
            }
        }
        assertEquals(
            emptyList(),
            offenders,
            "model/ 必须是纯 wire/DTO 层（今天只 import kotlinx.serialization），" +
                "不得依赖数据库、仓储、服务或路由层。" +
                "（G217b：db/ 此前不在禁止表里——它是 model/ 的最近邻居，" +
                "漏掉它等于把最容易发生的那类耦合留在了门外。实测当前 0 处。）",
        )
    }

    // ------------------------------------------------------------------
    // 棘轮
    // ------------------------------------------------------------------

    @Test
    fun `plugins must not own more transaction blocks than the frozen baseline`() {
        val actual = filesUnder("plugins")
            .map { it.name to TRANSACTION_BLOCK.findAll(it.codeText()).count() }
            .filter { it.second > 0 }
            .toMap()

        println("[arch] plugins transaction blocks = ${actual.values.sum()} in ${actual.size} files")

        assertRatchet(
            what = "plugins/ 内 handler 直写事务",
            actual = actual.mapValues { (_, v) -> v.toString() },
            baseline = frozenRouteTransactions.mapValues { (_, v) -> v.toString() },
            direction = "route 层不得新增 `transaction {`；把 SQL 下沉到 repository，由 service 组合。",
        )
    }

    @Test
    fun `plugins must not gain new files that touch Exposed`() {
        val actual = filesUnder("plugins")
            // G218b：判据从「代码文本里出现过 org.jetbrains.exposed」收紧成
            // 「真的有 import 行」。原判据把 StatusPages.kt 也算进来了——
            // 它只是在 catch 里写了个全限定名 `org.jetbrains.exposed.exceptions.ExposedSQLException`
            // 做错误映射，那不算「自己写 SQL」。
            // 原判据的害处是双向的：既让基线多一项（虚高），
            // 又让「往 StatusPages.kt 里加一条真 import」不被发现（余量）。
            .filter { EXPOSED_IMPORT.containsMatchIn(it.codeText()) }
            .map { it.name }
            .toSet()

        println("[arch] plugins importing Exposed = ${actual.size} files")

        assertRatchet(
            what = "plugins/ 内直接 import Exposed 的文件",
            actual = actual.asRatchet(),
            baseline = frozenPluginsImportingExposed.asRatchet(),
            direction = "新增 route 文件必须经 service/repository，不得自己写 SQL。",
        )
    }

    @Test
    fun `repository must not depend on plugins`() {
        val actual = filesUnder("repository")
            .map { it.name to PLUGINS_PACKAGE_REFERENCE.findAll(it.codeText()).count() }
            .filter { it.second > 0 }
            .toMap()

        assertRatchet(
            what = "repository/ → plugins/ 反向依赖",
            actual = actual.mapValues { (_, v) -> v.toString() },
            baseline = frozenRepositoryDependingOnPlugins.mapValues { (_, v) -> v.toString() },
            direction = "内层（repository）不得依赖外层（plugins）；把被依赖的常量/工具下沉到中立包。",
        )
    }

    @Test
    fun `service must not depend on plugins`() {
        val actual = filesUnder("service")
            .map { it.name to PLUGINS_PACKAGE_REFERENCE.findAll(it.codeText()).count() }
            .filter { it.second > 0 }
            .toMap()

        assertRatchet(
            what = "service/ → plugins/ 反向依赖",
            actual = actual.mapValues { (_, v) -> v.toString() },
            baseline = frozenServicesDependingOnPlugins.mapValues { (_, v) -> v.toString() },
            direction = "service 不得依赖 route 层；把被依赖的常量/工具下沉到中立包。",
        )
    }

    /**
     * G217b：`messaging/` 的**分层债**棘轮。
     *
     * `messaging must not depend on route layer plugins` 那条的 KDoc 把 messaging/
     * 称作「消息领域层」，但它实际上**直接用 Exposed DSL、直接 import db/ 表对象**
     * （实测 27 处 db 引用 + 大量 org.jetbrains.exposed import）。
     * 也就是说：那条门禁只覆盖了两个越界依赖里的一个，另一个从来没被记过。
     *
     * 这里不改成绝对禁止（那会立刻红，是一次大重构），而是**按现有棘轮模式冻结**：
     * 每文件的 db 引用数与 Exposed import 数都记成基线，只能降不能升。
     * 债要可见，才可能被还。
     */
    @Test
    fun `messaging db and exposed coupling only ever shrinks`() {
        val dbRefs = mutableMapOf<String, String>()
        val exposedRefs = mutableMapOf<String, String>()
        filesUnder("messaging").forEach { file ->
            val code = file.codeText()
            val db = DB_PACKAGE_REFERENCE.findAll(code).count()
            if (db > 0) dbRefs[file.name] = db.toString()
            val ex = EXPOSED_IMPORT.findAll(code).count()
            if (ex > 0) exposedRefs[file.name] = ex.toString()
        }
        assertRatchet(
            what = "messaging/ → db/ 表对象引用",
            actual = dbRefs,
            baseline = frozenMessagingDbRefs,
            direction = "把 SQL 下沉到 repository/，messaging/ 只依赖领域接口。" +
                "债先冻结可见，还一处就把基线下调一处。",
        )
        assertRatchet(
            what = "messaging/ 里的 Exposed import",
            actual = exposedRefs,
            baseline = frozenMessagingExposedImports,
            direction = "同上：Exposed DSL 属于 db/ 边界，不应出现在领域层。",
        )
    }

    @Test
    fun `repository package must not gain new service files`() {
        val actual = filesUnder("repository")
            .filter { it.name.endsWith("Service.kt") }
            .map { it.name }
            .toSet()

        assertRatchet(
            what = "错放在 repository/ 的 *Service.kt",
            actual = actual.asRatchet(),
            baseline = frozenServicesInRepositoryPackage.asRatchet(),
            direction = "新 service 放 service/；repository/ 只放 SQL 边界。",
        )
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    /**
     * 定位服务端源码根目录。
     *
     * 找不到时**直接失败**——源码扫描型门禁最危险的失败模式是「扫了个空目录所以通过」。
     */
    private val serverSourceRoot: File by lazy {
        val markers = listOf(
            "src/main/kotlin/com/maodouchat/server",
            "server/src/main/kotlin/com/maodouchat/server",
        )
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            markers.forEach { marker ->
                val candidate = File(dir, marker)
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = dir.parentFile
        }
        fail(
            "找不到服务端源码根目录（从 user.dir=${System.getProperty("user.dir")} 向上查找 " +
                "$markers 均失败）。架构门禁拒绝静默通过。"
        )
    }

    private fun filesUnder(relativePackage: String): List<File> {
        val dir = File(serverSourceRoot, relativePackage)
        assertTrue(
            dir.isDirectory,
            "架构门禁期待的包目录不存在：${dir.path}（拒绝在缺失目录上静默通过）。",
        )
        val files = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
        assertTrue(
            files.isNotEmpty(),
            "包目录 $relativePackage 下没有 .kt 文件，架构门禁无法给出有效结论。",
        )
        return files
    }

    private fun Set<String>.asRatchet(): Map<String, String> = associateWith { "1" }

    /**
     * 棘轮断言：`actual` 必须**精确等于** `baseline`。
     *
     * 变多 → 红；变少 → 也红，但提示这是改进，请下调基线（保证基线永远等于真实状态）。
     */
    private fun assertRatchet(
        what: String,
        actual: Map<String, String>,
        baseline: Map<String, String>,
        direction: String,
    ) {
        if (actual == baseline) return

        val added = (actual.keys - baseline.keys).sorted()
        val removed = (baseline.keys - actual.keys).sorted()
        val changed = actual.keys.intersect(baseline.keys)
            .filter { actual[it] != baseline[it] }
            .sorted()

        fail(
            buildString {
                appendLine("架构棘轮被打破：$what")
                appendLine("  实际 = ${actual.size} 项 / 基线 = ${baseline.size} 项")
                if (added.isNotEmpty()) {
                    appendLine("  新增（必须先改代码，不能改基线）：${added.joinToString()}")
                }
                if (removed.isNotEmpty()) {
                    appendLine("  已消除（好事，请下调基线）：${removed.joinToString()}")
                }
                if (changed.isNotEmpty()) {
                    appendLine(
                        "  数量变化：" + changed.joinToString { "$it: ${baseline[it]} -> ${actual[it]}" }
                    )
                }
                appendLine("  方向：$direction")
                appendLine(
                    "  基线位置：server/src/test/kotlin/com/maodouchat/server/architecture/" +
                        "ServerArchitectureTest.kt"
                )
            }
        )
    }

    private companion object {
        /**
         * 与 `grep -roE '\btransaction[ \t]*[({]'` 等价的文本口径。
         * 用 `[ \t]*` 而非 `\s*`，避免跨行匹配导致与 grep 人工复核结果不一致。
         */
        val TRANSACTION_BLOCK = Regex("""\btransaction[ \t]*[({]""")

        /** 与 `grep -o 'com\.maodouchat\.server\.plugins'` 等价的文本口径（按引用处计数）。 */
        val PLUGINS_PACKAGE_REFERENCE = Regex("""com\.maodouchat\.server\.plugins""")

        /** G217b：与 `grep -o 'com\.maodouchat\.server\.db'` 等价（按引用处计数）。 */
        val DB_PACKAGE_REFERENCE = Regex("""com\.maodouchat\.server\.db""")

        /** G217b：Exposed 的 import 行（`import org.jetbrains.exposed...`）。G218b 起也用于 plugins 判据。 */
        val EXPOSED_IMPORT = Regex("""^import\s+org\.jetbrains\.exposed""", RegexOption.MULTILINE)

        /**
         * G217b 冻结值：messaging/ → db/ 的按文件引用数。
         * 由门禁首次运行自报后填入（不手推——G167b 的教训）。
         */
        val frozenMessagingDbRefs: Map<String, String> = mapOf(
            "ConversationDeviceSnapshotStore.kt" to "4",
            "EnvelopeMailboxStore.kt" to "2",
            "MailboxRetentionService.kt" to "2",
            "MessageAdmissionPolicy.kt" to "6",
            "MessageMetadataStore.kt" to "8",
            "ServiceMessagePublisher.kt" to "5",
        )

        /** G217b 冻结值：messaging/ 里每文件的 Exposed import 数。 */
        val frozenMessagingExposedImports: Map<String, String> = mapOf(
            "ConversationDeviceSnapshotStore.kt" to "7",
            "EnvelopeMailboxStore.kt" to "9",
            "MailboxRetentionService.kt" to "11",
            "MessageAdmissionPolicy.kt" to "7",
            "MessageMetadataStore.kt" to "7",
            "ServiceMessagePublisher.kt" to "7",
        )
    }
}
