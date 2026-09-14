package com.maodouchat.core.testing

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M6：客户端热点棘轮门禁。
 *
 * 为什么要新建这个文件：本模块原有的 [ArchitectureTest]（A01 类级 ArchUnit 规则）
 * 有**两个**问题——
 * 1. `:core:testing` 被 include 进 settings，但**没有任何模块依赖它、CI 也从不调用它的 test 任务**
 *    （CI 只跑 `:app:testDebugUnitTest`、`checkArchitecture`、python 门禁、lint、assemble），
 *    所以那两条规则**从未在 CI 上执行过**；
 * 2. 即便执行，它们也几乎不可能失败：core/domain 模块的 build 文件里没有 androidx/app 依赖，
 *    想依赖 `com.maodouchat.ui..` 或 `androidx..` 的代码**根本编译不过**。真正该拦的
 *    「有人往 core 模块加 Android 依赖」这件事，是靠 build 文件而不是靠这两条规则。
 *
 * 所以这里改用**源码扫描 + 精确相等棘轮**，盯住项目台账里明确点名的客户端热点：
 * 数字只许下降；变多要红，变少也要红（提示下调基线）。这与服务端 `ServerArchitectureTest`
 * 的语义一致，也是这个项目已经验证过有效的做法。
 */
class ClientHotspotRatchetTest {

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory) {
                return@lazy dir
            }
            dir = dir.parentFile
        }
        fail("找不到仓库根（从 user.dir=${System.getProperty("user.dir")} 向上查找 settings.gradle.kts + app/ 失败）")
    }

    private fun source(relative: String): File {
        val file = File(repoRoot, relative)
        assertTrue(file.isFile, "热点文件不存在：$relative（路径写错就失去了守护意义）")
        return file
    }

    /** 台账点名的客户端热点。行数精确冻结，只许下降。 */
    private val hotspotBaselines: Map<String, Int> = mapOf(
        "app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailRoute.kt" to 5048,
        "app/src/main/java/com/maodouchat/ui/screen/chatdetail/ChatDetailViewModel.kt" to 3131,
        "app/src/main/java/com/maodouchat/util/GroupPlayPolicy.kt" to 2298,
    )

    @Test
    fun `client hotspot files may only shrink`() {
        val actual = hotspotBaselines.mapValues { (path, _) -> source(path).readLines().size }
        val drifted = actual.filter { (path, lines) -> hotspotBaselines[path] != lines }
        assertEquals(
            emptyMap(),
            drifted,
            "客户端热点行数变了。变多说明又往巨型文件里堆东西——请先拆出去；" +
                "变少是好消息，请把 ClientHotspotRatchetTest 里的基线改小（这是预期工作流）。",
        )
    }

    /**
     * UI 层不得直接摸数据库——**主口径，对齐台账 U02**。
     *
     * U02 的契约是「平台动作通过 effect handler 执行，**不在 Composable 内直接写库**」，
     * 所以真正该盯的是**含 `@Composable` 的文件**。G8 的初版把 ViewModel/Ports 用 DAO
     * 也算进同一个数字（200 处），那个数**不等于 U02 的达标口径**——口径混了两类债，
     * 会让台账谎报进度。这里拆开：本测试只看 `@Composable` 文件（实测 87 处 / 17 文件）。
     */
    @Test
    fun `composable files do not reach into the database directly`() {
        val actual = directPersistenceHits(onlyComposableFiles = true)
        assertEquals(
            composableDirectPersistenceBaseline,
            actual,
            "Composable 直连持久层/全局单例的次数变了。变多说明又绕过了 effect handler（U02 未达标）；" +
                "变少是好消息，请把基线改小。",
        )
    }

    /**
     * 次口径：整个 `ui/`（含 ViewModel / Ports）。
     *
     * ViewModel 直接持有 DAO 是**另一类**债（该走 ports），不该与 U02 混在一个数字里，
     * 但同样值得只减不增，所以单独冻结。
     */
    @Test
    fun `the whole ui layer keeps its direct persistence budget`() {
        val actual = directPersistenceHits(onlyComposableFiles = false)
        assertEquals(
            uiDirectPersistenceBaseline,
            actual,
            "ui/ 直连持久层/全局单例的总次数变了。变多请先问是不是又绕过了 ports/effect handler；" +
                "变少是好消息，请把基线改小。",
        )
    }

    private fun directPersistenceHits(onlyComposableFiles: Boolean): Map<String, Int> {
        val uiRoot = File(repoRoot, "app/src/main/java/com/maodouchat/ui")
        assertTrue(uiRoot.isDirectory, "UI 源码目录不存在：${uiRoot.path}")
        return uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file -> file.relativeTo(uiRoot).path to file.readText() }
            .filter { (_, text) -> !onlyComposableFiles || text.contains("@Composable") }
            .map { (path, text) ->
                path to DIRECT_PERSISTENCE_SYMBOLS.sumOf { needle ->
                    Regex(Regex.escape(needle)).findAll(text).count()
                }
            }
            .filter { (_, hits) -> hits > 0 }
            .toMap()
            .toSortedMap()
    }

    private companion object {
        val DIRECT_PERSISTENCE_SYMBOLS = listOf("database.", "secretChatDao", "MaodouchatApp")

        /**
         * U02 主口径：含 `@Composable` 的文件里的命中数。实测 **84 处 / 17 文件**，只许下降。
         */
        val composableDirectPersistenceBaseline: Map<String, Int> = mapOf(
            "navigation/CallNavigation.kt" to 9,
            "navigation/MainContainerRoute.kt" to 6,
            "navigation/NavGraph.kt" to 4,
            "screen/call/CallHistoryScreen.kt" to 4,
            "screen/chatdetail/AiTasksScreen.kt" to 9,
            "screen/chatdetail/ChatDetailRoute.kt" to 6,
            "screen/chatdetail/MediaCenterScreen.kt" to 6,
            "screen/chatdetail/StarredMessagesScreen.kt" to 6,
            "screen/chatlist/GlobalSearchScreen.kt" to 8,
            "screen/chatlist/NotificationCenterScreen.kt" to 2,
            "screen/contacts/ContactSubScreens.kt" to 4,
            "screen/groupplay/GroupChainScreen.kt" to 2,
            "screen/groupplay/GroupCheckinScreen.kt" to 2,
            "screen/groupplay/GroupPkScreen.kt" to 2,
            "screen/groupplay/GroupPollScreen.kt" to 2,
            "screen/settings/SettingsSubScreens.kt" to 11,
            "theme/Motion.kt" to 1,
        )

        /**
         * 当前实测基线（本轮 `ui/` 的 `database.` / `secretChatDao` / `MaodouchatApp` 命中数）。
         * 只在真正减少时下调。
         */
        val uiDirectPersistenceBaseline: Map<String, Int> = mapOf(
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
            "screen/chatdetail/ChatDetailRoute.kt" to 6,
            "screen/chatdetail/ChatDetailViewModel.kt" to 36,
            "screen/chatdetail/ChatExportController.kt" to 2,
            "screen/chatdetail/GroupDetailViewModel.kt" to 2,
            "screen/chatdetail/MediaCenterScreen.kt" to 6,
            "screen/chatdetail/StarredMessagesScreen.kt" to 6,
            "screen/chatlist/ChatListPorts.kt" to 26,
            "screen/chatlist/ChatListRealtimeCoordinator.kt" to 3,
            "screen/chatlist/ChatListUiState.kt" to 1,
            "screen/chatlist/GlobalSearchScreen.kt" to 8,
            "screen/chatlist/NotificationCenterScreen.kt" to 2,
            "screen/contacts/ContactSubScreens.kt" to 4,
            "screen/contacts/ContactsRepository.kt" to 3,
            "screen/contacts/ContactsViewModel.kt" to 10,
            "screen/contacts/MyQrCodeViewModel.kt" to 2,
            "screen/explore/ExploreViewModel.kt" to 1,
            "screen/groupplay/GroupChainScreen.kt" to 2,
            "screen/groupplay/GroupCheckinScreen.kt" to 2,
            "screen/groupplay/GroupPkScreen.kt" to 2,
            "screen/groupplay/GroupPollScreen.kt" to 2,
            "screen/login/LoginViewModel.kt" to 2,
            "screen/settings/SettingsSubScreens.kt" to 11,
            "screen/settings/SettingsSubViewModels.kt" to 4,
            "screen/settings/SettingsViewModel.kt" to 2,
            "theme/Motion.kt" to 1,
        )
    }
}
