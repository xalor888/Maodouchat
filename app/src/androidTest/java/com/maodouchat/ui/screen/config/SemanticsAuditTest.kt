package com.maodouchat.ui.screen.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.settings.repository.SecurityPreferences
import com.maodouchat.settings.repository.SecurityPreferencesPatch
import com.maodouchat.settings.repository.SettingsPrivacy
import com.maodouchat.settings.repository.SettingsPrivacyPatch
import com.maodouchat.settings.repository.SettingsProfile
import com.maodouchat.settings.repository.SettingsRepository
import com.maodouchat.settings.repository.SettingsSession
import com.maodouchat.ui.screen.settings.SecurityCoordinator
import com.maodouchat.ui.screen.settings.SettingsScreen
import com.maodouchat.ui.screen.settings.SettingsViewModel
import com.maodouchat.explore.policy.ExploreFeedPolicy
import com.maodouchat.explore.repository.FeedController
import com.maodouchat.explore.repository.FeedRepository
import com.maodouchat.explore.repository.FeedSession
import com.maodouchat.network.PostDto
import com.maodouchat.ui.screen.explore.ExploreOrchestrator
import com.maodouchat.ui.screen.explore.ExploreScreen
import com.maodouchat.ui.screen.explore.ExploreViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.maodouchat.ui.screen.call.CallScreen
import com.maodouchat.webrtc.CallState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * G342：**无障碍语义审计探针**——可点击节点必须有可朗读的名字，触控目标不小于 48dp。
 *
 * 为什么是一条**通用**探针而不是逐屏手写断言：TalkBack 与触控目标这两维
 * （总清单 Q03 无障碍项）覆盖多个屏幕时，手写「这个按钮叫什么」既写不完也写不准；
 * 这里直接遍历 Compose 语义树，用同一把尺子量每个可点击节点：
 *
 * - **名字**：`contentDescription` 或 `text` 至少有一个非空（TalkBack 读不出名字的按钮
 *   等于「按钮，未命名」）；
 * - **触控目标**：`touchBoundsInRoot` 的宽高 ≥ 48dp（Material 最小触控目标：
 *   低于它手指点不中，尤其是大字号下图标被文字挤压时）。
 *
 * **它已经抓到并修掉一个真缺陷**（G342 探针阶段实测）：ChatList 的搜索输入框
 * 在空值时**没有可朗读名字**——placeholder 是兄弟 `Text` 节点，不在输入框语义里，
 * TalkBack 读作「编辑框，未命名」。修法见 `ui/component/SearchBar.kt`：给
 * `BasicTextField` 挂 `semantics { contentDescription = placeholder }`。
 *
 * 纪律（先测量、再设阈值）：探针阶段先只打日志，用真实数据决定「补语义」还是
 * 「进白名单」，再把结论固化成断言；`selfCheck` 用例常驻，保证探针本身不失明。
 */
@RunWith(AndroidJUnit4::class)
class SemanticsAuditTest {

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        /** Material 最小触控目标（dp）。 */
        const val MIN_TOUCH_DP = 48f
    }

    private class Issue(val label: String, val detail: String)

    private fun density(): Float =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    /** 审计过的可点击节点数——0 说明探针瞎了，结论不可信（防自欺）。只计**已放置**（可见）节点。 */
    private var auditedClickables = 0

    /**
     * 未放置（0×0 边界）的可点击节点数——懒列表折叠在屏外、动画初帧等。
     * 它们既触摸不到、TalkBack 也读不到，不构成无障碍问题，探针**跳过两类检查**并单独计数，
     * 避免把「屏外内容」误报成「坏按钮」。
     */
    private var auditedUnplaced = 0

    /** 审计到的标题（heading）节点数——供「每屏至少一个标题」断言使用。 */
    private var auditedHeadings = 0

    /** 遍历语义树，收集「可点击但读不出名字」「触控目标过小」两类问题，并统计 heading。 */
    private fun audit(node: SemanticsNode, out: MutableList<Issue>) {
        val config = node.config
        if (config.contains(SemanticsProperties.Heading)) auditedHeadings += 1
        val clickable = config.contains(SemanticsActions.OnClick)
        val bounds = node.touchBoundsInRoot
        val placed = bounds.width > 0f && bounds.height > 0f
        if (clickable && placed) auditedClickables += 1
        if (clickable && !placed) auditedUnplaced += 1
        if (clickable && placed) {
            val texts = config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("|") { it.text }.orEmpty()
            val desc = config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString("|").orEmpty()
            val editable = config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
            val name = desc.ifBlank { texts }.ifBlank { editable }.trim()
            if (name.isBlank()) {
                val childTexts = mutableListOf<String>()
                fun collectTexts(n: SemanticsNode) {
                    n.config.getOrNull(SemanticsProperties.Text)?.forEach { childTexts += it.text }
                    n.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { childTexts += it }
                    n.children.forEach { collectTexts(it) }
                }
                collectTexts(node)
                val tags = node.config.getOrNull(SemanticsProperties.TestTag)
                val role = node.config.getOrNull(SemanticsProperties.Role)
                out += Issue(
                    "clickable-without-name",
                    "id=${node.id} bounds=${node.touchBoundsInRoot} role=$role tag=$tags " +
                        "descendantTexts=${childTexts.take(6)}",
                )
            }
            val d = density()
            val wDp = bounds.width / d
            val hDp = bounds.height / d
            if (wDp < MIN_TOUCH_DP || hDp < MIN_TOUCH_DP) {
                out += Issue(
                    "touch-target-too-small",
                    "${wDp.roundToInt()}x${hDp.roundToInt()}dp id=${node.id} name=$name",
                )
            }
        }
        node.children.forEach { audit(it, out) }
    }

    /** @return (可点击节点数, heading 数, 问题列表)。多 root 场景（弹窗/权限对话框）逐个审计。 */
    private fun auditScreen(tag: String): Triple<Int, Int, List<Issue>> {
        compose.waitForIdle()
        auditedClickables = 0
        auditedHeadings = 0
        auditedUnplaced = 0
        val issues = mutableListOf<Issue>()
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEach { audit(it, issues) }
        android.util.Log.i(
            "SemanticsAudit",
            "[$tag] 审计了 $auditedClickables 个可点击节点、$auditedHeadings 个 heading、" +
                "跳过 $auditedUnplaced 个未放置节点，问题 ${issues.size} 条",
        )
        return Triple(auditedClickables, auditedHeadings, issues)
    }

    private fun assertClean(tag: String, minClickables: Int, minHeadings: Int = 0) {
        val (count, headings, issues) = auditScreen(tag)
        assert(count >= minClickables) {
            "探针可能失明：[$tag] 只审计到 $count 个可点击节点（期望 ≥ $minClickables）"
        }
        assert(headings >= minHeadings) {
            "[$tag] 期望至少 $minHeadings 个 heading 节点（TalkBack 标题导航），实测 $headings"
        }
        assert(issues.isEmpty()) {
            "[$tag] 无障碍问题 ${issues.size} 条：\n" +
                issues.joinToString("\n") { "  - ${it.label}: ${it.detail}" }
        }
    }

    /**
     * 探针自检（负控制）：一个**故意没名字**的大点击区必须被抓到；
     * 名字齐全的屏幕必须 0 问题。没有这条，探针可能整体失明而全绿。
     */
    @Test
    fun probeDetectsDeliberatelyUnnamedButton() {
        compose.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(
                    // 真·无名字：可点击区里**没有任何** text/contentDescription/EditableText。
                    androidx.compose.ui.Modifier
                        .size(200.dp)
                        .clickable { }
                )
            }
        }
        val (count, _, issues) = auditScreen("self-check-unnamed")
        assert(count == 1) { "自检区应只有 1 个可点击节点，实际 $count" }
        assert(issues.size == 1 && issues.single().label == "clickable-without-name") {
            "自检失败：无名字点击区未被识别，实际问题=$issues"
        }
    }

    @Test
    fun chatListSemanticsAreClean() {
        // 与 ConfigRobustnessTest 同款构造：真 Application + 真 VM（默认参数会自己装配）。
        val viewModel = com.maodouchat.ui.screen.chatlist.ChatListViewModel(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as android.app.Application
        )
        compose.setContent {
            com.maodouchat.ui.screen.chatlist.ChatListScreen(
                viewModel = viewModel,
                onChatClick = {},
                onOpenGroupDetail = {},
                onOpenGlobalSearch = {},
                onOpenNotificationCenter = {},
                onNavigateToTab = {},
                onOpenScan = {},
            )
        }
        // 实测 12 个可点击（搜索框/文件夹 chips/顶栏图标/重试按钮…）；下界留 10 防装配漂移。
        assertClean("chatlist", minClickables = 10, minHeadings = 1)
    }

    @Test
    fun callScreenSemanticsAreClean() {
        compose.setContent {
            CallScreen(
                contactName = "alice",
                isIncoming = true,
                callState = CallState.RINGING,
            )
        }
        assertClean("call", minClickables = 2, minHeadings = 1)
    }

    // ---------- 第五、六屏：Settings / Explore（fake 复刻自 ConfigRobustnessTest 与 ExploreScreenUiTest） ----------

    private fun application(): android.app.Application =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as android.app.Application

    /** 复刻自 ConfigRobustnessTest（SettingsRepository 7 方法全返回空/成功值）。 */
    private fun fakeSettingsRepository() = object : SettingsRepository {
        private val session = SettingsSession("owner-1")
        override fun currentSession(): SettingsSession? = session
        override fun isCurrent(session: SettingsSession): Boolean = true
        override suspend fun loadProfile(session: SettingsSession): Result<SettingsProfile> =
            Result.success(
                SettingsProfile(
                    id = "u1", name = "alice", avatar = null,
                    status = "", username = null, isModerator = false,
                )
            )
        override suspend fun loadPrivacy(session: SettingsSession): Result<SettingsPrivacy> =
            Result.success(
                SettingsPrivacy(
                    showOnline = true, showStatus = true, searchable = true,
                    defaultPostVisibility = "PUBLIC", onlineVisibility = "EVERYONE",
                )
            )
        override suspend fun savePrivacy(
            session: SettingsSession, privacy: SettingsPrivacyPatch,
        ): Result<SettingsPrivacy> = Result.success(
            SettingsPrivacy(
                showOnline = true, showStatus = true, searchable = true,
                defaultPostVisibility = "PUBLIC", onlineVisibility = "EVERYONE",
            )
        )
        override suspend fun loadSecurityPreferences(
            session: SettingsSession,
        ): Result<SecurityPreferences> = Result.success(
            SecurityPreferences(
                appLockTimeoutMinutes = 5L, screenSecureEnabled = false, sensitiveGateEnabled = false,
            )
        )
        override suspend fun saveSecurityPreferences(
            session: SettingsSession,
            patch: SecurityPreferencesPatch,
        ): Result<SecurityPreferences> = Result.success(
            SecurityPreferences(
                appLockTimeoutMinutes = 5L, screenSecureEnabled = false, sensitiveGateEnabled = false,
            )
        )
    }

    @Test
    fun settingsScreenSemanticsAreClean() {
        val repo = fakeSettingsRepository()
        val vm = SettingsViewModel(
            application = application(),
            settingsRepository = repo,
            securityCoordinator = SecurityCoordinator(repo),
        )
        compose.setContent { SettingsScreen(viewModel = vm) }
        assertClean("settings", minClickables = 3, minHeadings = 1)
    }

    /** 复刻自 ExploreScreenUiTest（5 方法 FeedRepository，logged-in + 空 feed）。 */
    private fun fakeFeedRepository(): FeedRepository = object : FeedRepository {
        override fun currentSession(): FeedSession? = FeedSession("owner-1")
        override fun isCurrent(session: FeedSession): Boolean = true
        override suspend fun load(
            session: FeedSession,
            cursor: ExploreFeedPolicy.Cursor?,
        ): Result<List<PostDto>> = Result.success(emptyList())
        override suspend fun publish(
            session: FeedSession,
            content: String,
            imageUrls: List<String>,
            visibility: String?,
        ): Result<PostDto> = Result.failure(NotImplementedError("本测试不需要发布"))
    }

    @Test
    fun exploreScreenSemanticsAreClean() {
        val feedController = FeedController(fakeFeedRepository())
        val orchestrator = ExploreOrchestrator(
            application = application(),
            scope = CoroutineScope(Dispatchers.Main),
            feedController = feedController,
        )
        val vm = ExploreViewModel(
            application = application(),
            feedController = feedController,
            orchestrator = orchestrator,
        )
        compose.setContent { ExploreScreen(viewModel = vm) }
        assertClean("explore", minClickables = 2, minHeadings = 1)
    }

    // ---------- 第七、八屏：Contacts / Login（真 VM + 真 Application，装配同 ChatListViewModel 款） ----------

    @Test
    fun contactsScreenSemanticsAreClean() {
        val vm = com.maodouchat.ui.screen.contacts.ContactsViewModel(application())
        compose.setContent {
            com.maodouchat.ui.screen.contacts.ContactsScreen(viewModel = vm)
        }
        // 实测 10 个可点击（权限说明弹窗 + 空态入口 + 顶栏）；阈值取下界 8（探针纪律：先测量再设阈值）。
        assertClean("contacts", minClickables = 8, minHeadings = 1)
    }

    @Test
    fun loginScreenSemanticsAreClean() {
        val vm = com.maodouchat.ui.screen.login.LoginViewModel(application())
        compose.setContent {
            com.maodouchat.ui.screen.login.LoginScreen(viewModel = vm)
        }
        // 实测 8 个可点击；阈值取下界 6。
        assertClean("login", minClickables = 6, minHeadings = 1)
    }

    // ---------- 第九、十、十一屏：Starred / NotificationCenter / GlobalSearch（真 VM 直构） ----------

    @Test
    fun starredMessagesScreenSemanticsAreClean() {
        val vm = com.maodouchat.ui.screen.chatdetail.StarredMessagesViewModel(
            application(), androidx.lifecycle.SavedStateHandle(),
        )
        compose.setContent {
            com.maodouchat.ui.screen.chatdetail.StarredMessagesScreen(onBack = {}, viewModel = vm)
        }
        // 实测 2 个可点击（返回 + 列表项）。
        assertClean("starred", minClickables = 2, minHeadings = 1)
    }

    @Test
    fun notificationCenterScreenSemanticsAreClean() {
        val vm = com.maodouchat.ui.screen.chatlist.NotificationCenterViewModel(application())
        compose.setContent {
            com.maodouchat.ui.screen.chatlist.NotificationCenterScreen(onBack = {}, viewModel = vm)
        }
        // 实测 3 个可点击（返回 + 列表项）。
        assertClean("notifcenter", minClickables = 2, minHeadings = 1)
    }

    @Test
    fun globalSearchScreenSemanticsAreClean() {
        val vm = com.maodouchat.ui.screen.chatlist.GlobalSearchViewModel(application())
        compose.setContent {
            com.maodouchat.ui.screen.chatlist.GlobalSearchScreen(
                onBack = {},
                onOpenResult = { _, _ -> },
                viewModel = vm,
            )
        }
        // 实测 9 个可点击（返回 + 搜索框 + 过滤 chips 等）；阈值取下界 7。
        assertClean("globalsearch", minClickables = 7, minHeadings = 1)
    }
    // ---------- 第十二~十五屏：助手 / 动态 / 媒体中心 / AI 任务（2026-09-29 扩面） ----------

    @Test
    fun maodouAgentScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.ai.MaodouAgentScreen(onBack = {})
        }
        // 实测 8 个可点击（返回 + 输入/发送 + 建议 chips 等）；阈值取下界 6。
        assertClean("maodouagent", minClickables = 6)
    }

    @Test
    fun momentsScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.explore.MomentsScreen()
        }
        // 实测 2 个可点击（返回 + 发动态入口；空态下与 call 同量级）；阈值取下界 2。
        assertClean("moments", minClickables = 2)
    }

    @Test
    fun mediaCenterScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.chatdetail.MediaCenterScreen(onBack = {}, onOpenMessage = {})
        }
        // 实测 6 个可点击（返回 + 分类 chips + 空态重试等）；阈值取下界 4。
        assertClean("mediacenter", minClickables = 4)
    }

    @Test
    fun aiTasksScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.chatdetail.AiTasksScreen(onBack = {})
        }
        // 实测 2 个可点击（返回 + 筛选/搜索入口；空态下）；阈值取下界 2。
        assertClean("aitasks", minClickables = 2)
    }
    // ---------- 第十六~二十一屏：设置族（2026-09-29 扩面二） ----------

    @Test
    fun accountSecurityScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.AccountSecurityScreen()
        }
        // 实测 2 个可点击（首屏返回 + 首个开关行；其余 9 个在屏外未放置，探针跳过）。
        assertClean("accountsecurity", minClickables = 2)
    }

    @Test
    fun generalSettingsScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.GeneralSettingsScreen()
        }
        // 实测 22 个可点击（22 个可见 + 39 个屏外未放置被跳过）；阈值取下界 18。
        assertClean("generalsettings", minClickables = 18)
    }

    @Test
    fun notificationSettingsScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.NotificationSettingsScreen()
        }
        // 实测 11 个可点击（1 个屏外未放置被跳过）；阈值取下界 9。
        assertClean("notificationsettings", minClickables = 9)
    }

    @Test
    fun moderationScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.ModerationScreen()
        }
        // 实测 10 个可点击；阈值取下界 8。
        assertClean("moderation", minClickables = 8)
    }

    @Test
    fun blockedUsersScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.BlockedUsersScreen(onBack = {})
        }
        // 实测 2 个可点击（空态：返回 + 空态提示）；阈值取下界 2。
        assertClean("blockedusers", minClickables = 2)
    }

    @Test
    fun aboutScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.AboutScreen()
        }
        // 实测 3 个可点击（返回 + 版本行等）；阈值取下界 2。
        assertClean("about", minClickables = 2)
    }
    // ---------- 第二十二~三十屏：其余设置/工具族（2026-09-29 扩面三） ----------

    @Test
    fun callHistoryScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.call.CallHistoryScreen(onBack = {}, onCall = { _, _, _ -> })
        }
        // 实测 1 个可点击（仅返回；空态列表无可点行）；阈值取下界 1。
        assertClean("callhistory", minClickables = 1)
    }

    @Test
    fun myQrCodeScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.contacts.MyQrCodeScreen()
        }
        // 实测 8 个可点击（返回 + 保存/分享等）；阈值取下界 6。
        assertClean("myqrcode", minClickables = 6)
    }

    @Test
    fun scanScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.contacts.ScanScreen()
        }
        // 实测本机 4 个可点击（返回 + 手电/相册等）、CI 模拟器 2 个（无摄像头能力降级）；阈值取下界 2。
        assertClean("scan", minClickables = 2)
    }

    @Test
    fun themeEditorScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.ThemeEditorScreen()
        }
        // 实测 21 个可点击（色槽/预设/保存等）；阈值取下界 17。
        assertClean("themeeditor", minClickables = 17)
    }

    @Test
    fun themeWorkbenchScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.ThemeWorkbenchScreen()
        }
        // 实测 6 个可点击；阈值取下界 5。
        assertClean("themeworkbench", minClickables = 5)
    }

    @Test
    fun watermarkForensicScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.WatermarkForensicScreen(onBack = {})
        }
        // 实测 2 个可点击（返回 + 空态/刷新）；阈值取下界 2。
        assertClean("watermarkforensic", minClickables = 2)
    }

    @Test
    fun aiPrivacyScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.AiPrivacySettingsScreen()
        }
        // 实测 9 个可点击（19 个屏外未放置被跳过）；阈值取下界 7。
        assertClean("aiprivacy", minClickables = 7)
    }

    @Test
    fun myReportsScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.MyReportsScreen()
        }
        // 实测 2 个可点击（空态）；阈值取下界 2。
        assertClean("myreports", minClickables = 2)
    }

    @Test
    fun serverSettingsScreenSemanticsAreClean() {
        compose.setContent {
            com.maodouchat.ui.screen.settings.ServerSettingsScreen()
        }
        // 实测 5 个可点击；阈值取下界 4。
        assertClean("serversettings", minClickables = 4)
    }

    // ---------- 第三十一屏：会话详情（主聊天屏，真 Room 播种 + 真 VM） ----------

    @Test
    fun chatDetailScreenSemanticsAreClean() {
        // 与 ChatDetailScreenDataTest 同款构造：真 Room 播种 + SavedStateHandle 带 chatId 的真 VM。
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val db = com.maodouchat.data.local.AppDatabase.getInstance(ctx)
        kotlinx.coroutines.runBlocking {
            db.chatDao().deleteAllChats()
            db.chatDao().insertChats(
                listOf(
                    com.maodouchat.data.local.entity.ChatEntity(
                        id = "audit-chat-1",
                        isGroup = false,
                        lastMessage = "hi",
                        lastMessageTime = 1_000L,
                    )
                )
            )
        }
        val vm = com.maodouchat.ui.screen.chatdetail.ChatDetailViewModel(
            application(),
            androidx.lifecycle.SavedStateHandle(mapOf("chatId" to "audit-chat-1")),
        )
        compose.setContent {
            com.maodouchat.ui.screen.chatdetail.ChatDetailRoute(viewModel = vm)
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(com.maodouchat.R.string.common_back)
            )).fetchSemanticsNodes().isNotEmpty()
        }
        try {
            // 实测 11 个可点击（顶栏 6 + 输入区 5；空时间线）；阈值取下界 9。
            assertClean("chatdetail", minClickables = 9)
        } finally {
            // 放在 finally：用例失败（如探针报问题）也必须清干净，否则残留会话行
            // 会污染后面的 chatlist 审计（本轮实测踩过：超时失败 → 残留行 → chatlist 红）。
            kotlinx.coroutines.runBlocking { db.chatDao().deleteAllChats() }
        }
    }
}
