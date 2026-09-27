package com.maodouchat.ui.screen.config

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import com.maodouchat.ui.screen.chatlist.ChatListScreen
import com.maodouchat.ui.screen.chatlist.ChatListViewModel
import com.maodouchat.ui.screen.settings.SecurityCoordinator
import com.maodouchat.settings.repository.SettingsPrivacy
import com.maodouchat.settings.repository.SettingsPrivacyPatch
import com.maodouchat.settings.repository.SettingsProfile
import com.maodouchat.settings.repository.SettingsRepository
import com.maodouchat.ui.screen.settings.SettingsScreen
import com.maodouchat.settings.repository.SettingsSession
import com.maodouchat.ui.screen.settings.SettingsViewModel
import androidx.compose.runtime.Composable
import com.maodouchat.ui.screen.call.CallScreen
import com.maodouchat.webrtc.CallState
import com.maodouchat.ui.screen.explore.ExploreScreen
import com.maodouchat.ui.screen.explore.ExploreViewModel
import com.maodouchat.explore.repository.FeedController
import com.maodouchat.explore.repository.FeedRepository
import com.maodouchat.explore.policy.ExploreFeedPolicy
import com.maodouchat.ui.screen.explore.ExploreOrchestrator
import com.maodouchat.explore.repository.FeedSession
import com.maodouchat.network.PostDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.maodouchat.settings.repository.SecurityPreferences
import com.maodouchat.settings.repository.SecurityPreferencesPatch

/**
 * G325c：**配置健壮性探针**——屏幕在 RTL 与大字体下是否仍能渲染。
 *
 * 为什么做这个而不是像素截图：Q03 第 2 项要的是「浅/深色、手机/平板、横屏、
 * 大字体、RTL、中英文」的截图覆盖，但仓库里**零截图基建**
 * （无 paparazzi / roborazzi），像素回归需要新增构建依赖。
 * 本轮走零依赖的路：用 `CompositionLocalProvider` 覆盖配置，
 * 断言「屏幕不崩 + 关键内容仍在」——这捕捉的是一类真实缺陷：
 * 异常配置下崩溃、或文字被截断/丢失（只测默认配置时永远看不见）。
 *
 * **本轮最大的自欺风险是「配置其实没生效、测试在默认配置下空跑」。**
 * 所以每个用例都**先把生效的配置值捕获出来断言**（`capturedDirection` /
 * `capturedFontScale`），再断言关键节点仍在。没有前者，后者毫无意义。
 */
@RunWith(AndroidJUnit4::class)
class ConfigRobustnessTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun application(): android.app.Application =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as android.app.Application

    /** 被探针捕获到的「实际生效」的配置值——用来证明覆盖真的生效了。 */
    private var capturedDirection: LayoutDirection? = null
    private var capturedFontScale: Float? = null

    /**
     * 在 composable **之外**构造 VM——`setContent { }` 与这里的 `var content` 都是
     * composable lambda，在里面构造 VM 会触发 lint 的
     * `ViewModelConstructorInComposable`（G325c 实测：CI 的 `lintDebug` 因此红过一次）。
     */
    private fun chatListViewModel(): ChatListViewModel =
        ChatListViewModel(application())

    private fun setChatListScreenUnder(
        direction: LayoutDirection? = null,
        fontScale: Float? = null,
    ) {
        val viewModel = chatListViewModel()
        compose.setContent {
            // 逐层覆盖：RTL 与字体缩放互不依赖，可分别施加。
            var content: @androidx.compose.runtime.Composable () -> Unit = {
                // 捕获实际生效值——这是「配置真的生效」的唯一证据。
                capturedDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
                capturedFontScale = LocalConfiguration.current.fontScale
                ChatListScreen(
                    viewModel = viewModel,
                    onChatClick = {},
                    onOpenGroupDetail = {},
                    onOpenGlobalSearch = {},
                    onOpenNotificationCenter = {},
                    onNavigateToTab = {},
                    onOpenScan = {},
                )
            }
            if (fontScale != null) {
                val base = LocalConfiguration.current
                val scaled = Configuration(base).apply { this.fontScale = fontScale }
                val inner = content
                content = {
                    CompositionLocalProvider(LocalConfiguration provides scaled) { inner() }
                }
            }
            if (direction != null) {
                val inner = content
                content = {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) { inner() }
                }
            }
            content()
        }
        compose.waitForIdle()
    }

    private fun assertFolderChipsStillThere() {
        // 常驻 chrome 的四个系统文件夹 chip——与数据无关，稳定存在。
        compose.onNodeWithText(str(R.string.chat_folder_all)).assertExists()
        compose.onNodeWithText(str(R.string.chat_folder_unread)).assertExists()
        compose.onNodeWithText(str(R.string.chat_folder_groups)).assertExists()
        compose.onNodeWithText(str(R.string.chat_folder_direct)).assertExists()
    }

    @Test
    fun chatListScreenRendersUnderRtl() {
        setChatListScreenUnder(direction = LayoutDirection.Rtl)

        // ① 先证明配置真的生效——否则后面全是空跑
        assert(capturedDirection == LayoutDirection.Rtl) {
            "RTL 覆盖未生效：实际捕获到 $capturedDirection"
        }
        // ② 再证明屏幕仍渲染出关键内容
        assertFolderChipsStillThere()
    }

    @Test
    fun chatListScreenRendersUnderLargeFontScale() {
        setChatListScreenUnder(fontScale = 2.0f)

        assert(capturedFontScale == 2.0f) {
            "大字体覆盖未生效：实际捕获到 fontScale=$capturedFontScale"
        }
        assertFolderChipsStillThere()
    }

    // ---------- 第二个屏幕：SettingsScreen（有分组入口，比纯 chrome 更有信息量） ----------

    /** SettingsRepository 只有 7 个方法，全部返回空/成功值（与 SettingsScreenUiTest 同款）。 */
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
        ): Result<com.maodouchat.settings.repository.SecurityPreferences> = Result.success(
            com.maodouchat.settings.repository.SecurityPreferences(
                appLockTimeoutMinutes = 5L, screenSecureEnabled = false, sensitiveGateEnabled = false,
            )
        )
        override suspend fun saveSecurityPreferences(
            session: SettingsSession,
            patch: com.maodouchat.settings.repository.SecurityPreferencesPatch,
        ): Result<com.maodouchat.settings.repository.SecurityPreferences> = Result.success(
            com.maodouchat.settings.repository.SecurityPreferences(
                appLockTimeoutMinutes = 5L, screenSecureEnabled = false, sensitiveGateEnabled = false,
            )
        )
    }

    private fun buildSettingsViewModel(): SettingsViewModel {
        val repo = fakeSettingsRepository()
        return SettingsViewModel(
            application = application(),
            settingsRepository = repo,
            securityCoordinator = SecurityCoordinator(repo),
        )
    }

    private fun setSettingsScreenUnder(
        direction: LayoutDirection? = null,
        fontScale: Float? = null,
    ) {
        val viewModel = buildSettingsViewModel()
        compose.setContent {
            var content: @androidx.compose.runtime.Composable () -> Unit = {
                capturedDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
                capturedFontScale = LocalConfiguration.current.fontScale
                SettingsScreen(viewModel = viewModel)
            }
            if (fontScale != null) {
                val scaled = Configuration(LocalConfiguration.current).apply {
                    this.fontScale = fontScale
                }
                val inner = content
                content = { CompositionLocalProvider(LocalConfiguration provides scaled) { inner() } }
            }
            if (direction != null) {
                val inner = content
                content = { CompositionLocalProvider(LocalLayoutDirection provides direction) { inner() } }
            }
            content()
        }
        compose.waitForIdle()
    }

    @Test
    fun settingsScreenRendersUnderRtl() {
        setSettingsScreenUnder(direction = LayoutDirection.Rtl)

        assert(capturedDirection == LayoutDirection.Rtl) {
            "RTL 覆盖未生效：实际捕获到 $capturedDirection"
        }
        // 设置页的标题与若干分组入口在 RTL 下仍须渲染
        compose.onNodeWithText(str(R.string.settings_title)).assertExists()
        compose.onNodeWithText(str(R.string.settings_account_security)).assertExists()
        compose.onNodeWithText(str(R.string.settings_my_reports)).assertExists()
    }

    @Test
    fun settingsScreenRendersUnderLargeFontScale() {
        setSettingsScreenUnder(fontScale = 2.0f)

        assert(capturedFontScale == 2.0f) {
            "大字体覆盖未生效：实际捕获到 fontScale=$capturedFontScale"
        }
        compose.onNodeWithText(str(R.string.settings_title)).assertExists()
        compose.onNodeWithText(str(R.string.settings_account_security)).assertExists()
    }

    // ---------- 通用配置外壳：后续屏幕不再各自复制一遍覆盖逻辑 ----------

    /**
     * 与上面两个专用 setter 同语义的通用版：先按需包 RTL / fontScale 两层
     * `CompositionLocalProvider`，再渲染 [content]；`captured*` 由调用方在 content 里读。
     *
     * 为什么旧的两个 setter 不合并进来：它们是已绿的既有用例的实现，动它们没有收益；
     * 新用例统一走这里，避免再出现第三、第四份复制。
     */
    private fun setContentUnder(
        direction: LayoutDirection? = null,
        fontScale: Float? = null,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            var wrapped: @Composable () -> Unit = content
            if (fontScale != null) {
                val scaled = Configuration(LocalConfiguration.current).apply {
                    this.fontScale = fontScale
                }
                val inner = wrapped
                wrapped = { CompositionLocalProvider(LocalConfiguration provides scaled) { inner() } }
            }
            if (direction != null) {
                val inner = wrapped
                wrapped = { CompositionLocalProvider(LocalLayoutDirection provides direction) { inner() } }
            }
            wrapped()
        }
        compose.waitForIdle()
    }

    // ---------- 第三个屏幕：CallScreen（纯数据参数 + 回调，无需 fake VM） ----------

    @Test
    fun callScreenRendersUnderRtl() {
        setContentUnder(direction = LayoutDirection.Rtl) {
            capturedDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
            capturedFontScale = LocalConfiguration.current.fontScale
            CallScreen(
                contactName = "alice",
                isIncoming = true,
                callState = CallState.RINGING,
            )
        }

        assert(capturedDirection == LayoutDirection.Rtl) {
            "RTL 覆盖未生效：实际捕获到 $capturedDirection"
        }
        // 来电响铃的两个按钮（带 contentDescription 的图标）在 RTL 下仍须存在
        compose.onNodeWithContentDescription(str(R.string.call_accept)).assertExists()
        compose.onNodeWithContentDescription(str(R.string.call_hang_up)).assertExists()
    }

    @Test
    fun callScreenRendersUnderLargeFontScale() {
        setContentUnder(fontScale = 2.0f) {
            capturedDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
            capturedFontScale = LocalConfiguration.current.fontScale
            CallScreen(
                contactName = "alice",
                isIncoming = true,
                callState = CallState.RINGING,
            )
        }

        assert(capturedFontScale == 2.0f) {
            "大字体覆盖未生效：实际捕获到 fontScale=$capturedFontScale"
        }
        compose.onNodeWithContentDescription(str(R.string.call_hang_up)).assertExists()
    }

    // ---------- 第四个屏幕：ExploreScreen（fake FeedRepository 5 方法） ----------

    /** 复刻自 ExploreScreenUiTest（G309c）——logged-in + 空 feed，最小可渲染态。 */
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

    private fun buildExploreViewModel(): ExploreViewModel {
        val feedController = FeedController(fakeFeedRepository())
        val orchestrator = ExploreOrchestrator(
            application = application(),
            scope = CoroutineScope(Dispatchers.Main),
            feedController = feedController,
        )
        return ExploreViewModel(
            application = application(),
            feedController = feedController,
            orchestrator = orchestrator,
        )
    }

    @Test
    fun exploreScreenRendersUnderRtl() {
        val viewModel = buildExploreViewModel()
        setContentUnder(direction = LayoutDirection.Rtl) {
            capturedDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
            capturedFontScale = LocalConfiguration.current.fontScale
            ExploreScreen(viewModel = viewModel)
        }

        assert(capturedDirection == LayoutDirection.Rtl) {
            "RTL 覆盖未生效：实际捕获到 $capturedDirection"
        }
        // 顶栏标题 + 空态三件套在 RTL 下仍须渲染
        compose.onNodeWithText(str(R.string.nav_explore)).assertExists()
        compose.onNodeWithText(str(R.string.explore_empty_title)).assertExists()
    }

    @Test
    fun exploreScreenRendersUnderLargeFontScale() {
        val viewModel = buildExploreViewModel()
        setContentUnder(fontScale = 2.0f) {
            capturedDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
            capturedFontScale = LocalConfiguration.current.fontScale
            ExploreScreen(viewModel = viewModel)
        }

        assert(capturedFontScale == 2.0f) {
            "大字体覆盖未生效：实际捕获到 fontScale=$capturedFontScale"
        }
        compose.onNodeWithText(str(R.string.explore_empty_action)).assertExists()
    }
}
