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
 * route→service→repository」写成中心契约，但实测 `plugins/` 里躺着 75 处 handler 内直写事务、
 * 36 个 plugin 文件直接 import Exposed，另有 `repository/`→`plugins/` 反向依赖。
 * 只写在文档里的契约不是契约——这份测试把它变成**会失败的东西**。
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

    /** `plugins/` 下每个文件的 `transaction {` 出现次数。当前合计 37 处 / 16 个文件。 */
    private val frozenRouteTransactions: Map<String, Int> = mapOf(
        "DeveloperRouting.kt" to 4,
        "AnnouncementRouting.kt" to 4,
        "AdminDiagnosticsRouting.kt" to 4,
        "AdminUsersRouting.kt" to 3,
        "AdminBulkRouting.kt" to 3,
        "AdminModerationRouting.kt" to 2,
        "AdminContentRouting.kt" to 2,
        "AdminChatsRouting.kt" to 2,
        "UserTagRouting.kt" to 1,
        "SignalKeyRouting.kt" to 1,
        "PollRouting.kt" to 1,
        "HealthRoutes.kt" to 1,
        "GroupAdministrationRouting.kt" to 1,
        "AdminSystemRouting.kt" to 1,
        "AdminSupport.kt" to 1,
    )

    /** `plugins/` 下直接引用 Exposed（`org.jetbrains.exposed`）的文件。当前 34 个。 */
    private val frozenPluginsImportingExposed: Set<String> = setOf(
        "AdminBulkRouting.kt",
        "AdminChatsRouting.kt",
        "AdminContentRouting.kt",
        "AdminDiagnosticsRouting.kt",
        "AdminModerationRouting.kt",
        "AdminSupport.kt",
        "AdminSystemRouting.kt",
        "AdminUsersRouting.kt",
        "AnnouncementRouting.kt",
        "BotChatInviteRouting.kt",
        "BotChatMiscRouting.kt",
        "BotCoreRouting.kt",
        "BotFanout.kt",
        "BotGeoRouting.kt",
        "BotInfoRouting.kt",
        "BotMediaRouting.kt",
        "BotMessagingVariantsRouting.kt",
        "BotPollEditRouting.kt",
        "BotPollQuizRouting.kt",
        "BotPresentationCardsRouting.kt",
        "BotPresentationRouting.kt",
        "BotPresentationStatusRouting.kt",
        "BotPresentationWidgetsRouting.kt",
        "BotReactionRouting.kt",
        "DeveloperRouting.kt",
        "GroupAdministrationRouting.kt",
        "HealthRoutes.kt",
        "PollRouting.kt",
        "PublicProfileHtml.kt",
        "Routing.kt",
        "SignalKeyRouting.kt",
        "StatusPages.kt",
        "UserTagRouting.kt",
    )

    /**
     * `repository/` 反向依赖 `plugins/` 的**引用处数**（非仅文件数）。当前 3 处 / 2 个文件。
     *
     * 按处数而非文件数冻结，是为了堵住「在已违规文件里再加一条引用」这条绕过路径。
     */
    private val frozenRepositoryDependingOnPlugins: Map<String, Int> = mapOf(
        "BotRepository.kt" to 1,
        "RateLimitStatsRepository.kt" to 2,
    )

    /**
     * `service/` 反向依赖 `plugins/` 的**引用处数**。当前 6 处 / 3 个文件。
     *
     * `CallSignalingService` 3 处、`OrphanGcJob` 2 处、`BotWebhookService` 1 处。
     * M2 第二步顺带消除了 `MaintenanceRunner` → `plugins.purgeAdminOperationalData`
     * 这条倒置：清理函数已迁到 `repository/AdminOperationalDataPurge.kt`。
     */
    private val frozenServicesDependingOnPlugins: Map<String, Int> = mapOf(
        "BotWebhookService.kt" to 1,
        "CallSignalingService.kt" to 3,
        "OrphanGcJob.kt" to 2,
    )

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
    // 绝对不变量
    // ------------------------------------------------------------------

    @Test
    fun `messaging must not depend on route layer plugins`() {
        val offenders = filesUnder("messaging")
            .filter { it.readText().contains("com.maodouchat.server.plugins") }
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
            "com.maodouchat.server.repository" to "repository",
            "com.maodouchat.server.service" to "service",
            "com.maodouchat.server.plugins" to "plugins",
        )
        val offenders = mutableListOf<String>()
        filesUnder("model").forEach { file ->
            val text = file.readText()
            forbidden.forEach { (needle, label) ->
                if (text.contains(needle)) offenders += "${file.name} -> $label"
            }
        }
        assertEquals(
            emptyList(),
            offenders,
            "model/ 必须是纯 wire/DTO 层（今天只 import kotlinx.serialization），" +
                "不得依赖数据库、仓储、服务或路由层。",
        )
    }

    // ------------------------------------------------------------------
    // 棘轮
    // ------------------------------------------------------------------

    @Test
    fun `plugins must not own more transaction blocks than the frozen baseline`() {
        val actual = filesUnder("plugins")
            .map { it.name to TRANSACTION_BLOCK.findAll(it.readText()).count() }
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
            .filter { it.readText().contains("org.jetbrains.exposed") }
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
            .map { it.name to PLUGINS_PACKAGE_REFERENCE.findAll(it.readText()).count() }
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
            .map { it.name to PLUGINS_PACKAGE_REFERENCE.findAll(it.readText()).count() }
            .filter { it.second > 0 }
            .toMap()

        assertRatchet(
            what = "service/ → plugins/ 反向依赖",
            actual = actual.mapValues { (_, v) -> v.toString() },
            baseline = frozenServicesDependingOnPlugins.mapValues { (_, v) -> v.toString() },
            direction = "service 不得依赖 route 层；把被依赖的常量/工具下沉到中立包。",
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
    }
}
