package com.maodouchat.ui.screen.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.maodouchat.settings.repository.SecurityPreferences
import com.maodouchat.settings.repository.SecurityPreferencesPatch
import com.maodouchat.settings.repository.SettingsPrivacy
import com.maodouchat.settings.repository.SettingsPrivacyPatch
import com.maodouchat.settings.repository.SettingsProfile
import com.maodouchat.settings.repository.SettingsRepository
import com.maodouchat.settings.repository.SettingsSession
import com.maodouchat.explore.repository.FeedRepository

/**
 * G315c：`SettingsScreen` 本体的 **UI 层**覆盖（Q03 第 1 项的第七个入口）。
 *
 * 形态：`SettingsScreen(..., viewModel: SettingsViewModel = viewModel())`——
 * 与 Contacts/Explore 同一形状。`SettingsViewModel`（`SettingsViewModel.kt:77-81`）的三个依赖：
 * `SettingsRepository` 是**只有 7 个方法**的接口，`SecurityCoordinator` 又是收该接口的类，
 * 所以 fake 一个 7 方法接口即可构造整条链（与 G309c 的 `FeedRepository` 同量级）。
 * **`ChatListPorts` 那种 private 构造器 + 具体类协作者的情况在这里不存在。**
 *
 * 覆盖：标题渲染 + 若干分组入口的**可见性与行为**（点哪项触发哪个 `onOpenXxx`）。
 * 这些是屏幕级接线——JVM 层的 ViewModel 测试碰不到，行级 composable 测试也没有。
 *
 * 纪律同 G301c：文案一律 `R.string`；每例同时断言可见性与行为。
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val session = SettingsSession("owner-1")

    /** 7 方法接口，全部返回空/成功值——本测试只关心 UI 渲染与回调。 */
    private fun createFakeSettingsRepository() = object : SettingsRepository {
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
            session: SettingsSession, patch: SecurityPreferencesPatch,
        ): Result<SecurityPreferences> = Result.success(
            SecurityPreferences(
                appLockTimeoutMinutes = 5L, screenSecureEnabled = false, sensitiveGateEnabled = false,
            )
        )
    }

    private fun buildScreenViewModel(): SettingsViewModel {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as android.app.Application
        val repo = createFakeSettingsRepository()
        return SettingsViewModel(
            application = application,
            settingsRepository = repo,
            securityCoordinator = SecurityCoordinator(repo),
        )
    }

    // ---------- 可行性探针 ----------

    @Test
    fun settingsScreenRendersItsTitleWithAnInjectedViewModel() {
        compose.setContent { SettingsScreen(viewModel = buildScreenViewModel()) }
        compose.waitForIdle()
        // 证明「显式传 VM」这条路走得通：默认的 viewModel() 不会被求值。
        compose.onNodeWithText(str(R.string.settings_title)).assertIsDisplayed()
    }

    // ---------- 分组入口：可见性 + 屏幕级行为 ----------

    @Test
    fun accountSecurityEntryFiresTheScreensCallback() {
        var opened = 0
        compose.setContent {
            SettingsScreen(viewModel = buildScreenViewModel(), onOpenAccountSecurity = { opened++ })
        }
        compose.waitForIdle()

        val label = str(R.string.settings_account_security)
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText(label).performClick()
        assert(opened == 1) { "点「账号安全」后 onOpenAccountSecurity 应为 1，实际 $opened" }
    }

    @Test
    fun myReportsEntryFiresTheScreensCallback() {
        var opened = 0
        compose.setContent {
            SettingsScreen(viewModel = buildScreenViewModel(), onOpenMyReports = { opened++ })
        }
        compose.waitForIdle()

        val label = str(R.string.settings_my_reports)
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText(label).performClick()
        assert(opened == 1) { "点「我的举报」后 onOpenMyReports 应为 1，实际 $opened" }
    }

    @Test
    fun blockedUsersEntryFiresTheScreensCallback() {
        var opened = 0
        compose.setContent {
            SettingsScreen(viewModel = buildScreenViewModel(), onOpenBlockedUsers = { opened++ })
        }
        compose.waitForIdle()

        val label = str(R.string.settings_blocked_users)
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText(label).performClick()
        assert(opened == 1) { "点「黑名单」后 onOpenBlockedUsers 应为 1，实际 $opened" }
    }

    @Test
    fun myQrCodeEntryFiresTheScreensCallback() {
        var opened = 0
        compose.setContent {
            SettingsScreen(viewModel = buildScreenViewModel(), onOpenMyQrCode = { opened++ })
        }
        compose.waitForIdle()

        val label = str(R.string.profile_my_qr)
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText(label).performClick()
        assert(opened == 1) { "点「我的二维码」后 onOpenMyQrCode 应为 1，实际 $opened" }
    }

    @Test
    fun starredMessagesEntryFiresTheScreensCallback() {
        var opened = 0
        compose.setContent {
            SettingsScreen(viewModel = buildScreenViewModel(), onOpenStarredMessages = { opened++ })
        }
        compose.waitForIdle()

        val label = str(R.string.settings_starred_messages)
        compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText(label).performClick()
        assert(opened == 1) { "点「我的收藏」后 onOpenStarredMessages 应为 1，实际 $opened" }
    }
}
