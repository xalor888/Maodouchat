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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

    /** 审计过的可点击节点数——0 说明探针瞎了，结论不可信（防自欺）。 */
    private var auditedClickables = 0

    /** 遍历语义树，收集「可点击但读不出名字」与「触控目标过小」两类问题。 */
    private fun audit(node: SemanticsNode, out: MutableList<Issue>) {
        val config = node.config
        val clickable = config.contains(SemanticsActions.OnClick)
        if (clickable) auditedClickables += 1
        if (clickable) {
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
            val wDp = node.touchBoundsInRoot.width / d
            val hDp = node.touchBoundsInRoot.height / d
            if (wDp < MIN_TOUCH_DP || hDp < MIN_TOUCH_DP) {
                out += Issue(
                    "touch-target-too-small",
                    "${wDp.roundToInt()}x${hDp.roundToInt()}dp id=${node.id} name=$name",
                )
            }
        }
        node.children.forEach { audit(it, out) }
    }

    /** @return (审计到的可点击节点数, 问题列表)。 */
    private fun auditScreen(tag: String): Pair<Int, List<Issue>> {
        compose.waitForIdle()
        auditedClickables = 0
        val issues = mutableListOf<Issue>()
        audit(compose.onRoot().fetchSemanticsNode(), issues)
        android.util.Log.i(
            "SemanticsAudit",
            "[$tag] 审计了 $auditedClickables 个可点击节点，问题 ${issues.size} 条",
        )
        return auditedClickables to issues
    }

    private fun assertClean(tag: String, minClickables: Int) {
        val (count, issues) = auditScreen(tag)
        assert(count >= minClickables) {
            "探针可能失明：[$tag] 只审计到 $count 个可点击节点（期望 ≥ $minClickables）"
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
        val (count, issues) = auditScreen("self-check-unnamed")
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
        assertClean("chatlist", minClickables = 10)
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
        assertClean("call", minClickables = 2)
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
        assertClean("settings", minClickables = 3)
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
        assertClean("explore", minClickables = 2)
    }
}
