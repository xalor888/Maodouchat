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
import com.maodouchat.ui.screen.settings.SettingsPrivacy
import com.maodouchat.ui.screen.settings.SettingsPrivacyPatch
import com.maodouchat.ui.screen.settings.SettingsProfile
import com.maodouchat.ui.screen.settings.SettingsRepository
import com.maodouchat.ui.screen.settings.SettingsScreen
import com.maodouchat.ui.screen.settings.SettingsSession
import com.maodouchat.ui.screen.settings.SettingsViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        ): Result<com.maodouchat.ui.screen.settings.SecurityPreferences> = Result.success(
            com.maodouchat.ui.screen.settings.SecurityPreferences(
                appLockTimeoutMinutes = 5L, screenSecureEnabled = false, sensitiveGateEnabled = false,
            )
        )
        override suspend fun saveSecurityPreferences(
            session: SettingsSession,
            patch: com.maodouchat.ui.screen.settings.SecurityPreferencesPatch,
        ): Result<com.maodouchat.ui.screen.settings.SecurityPreferences> = Result.success(
            com.maodouchat.ui.screen.settings.SecurityPreferences(
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
}
