package com.maodouchat.ui.smoke

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.ChatEntity
import com.maodouchat.ui.screen.ai.MaodouAgentScreen
import com.maodouchat.ui.screen.call.CallHistoryScreen
import com.maodouchat.ui.screen.chatdetail.AiTasksScreen
import com.maodouchat.ui.screen.chatdetail.MediaCenterScreen
import com.maodouchat.ui.screen.chatdetail.StarredMessagesScreen
import com.maodouchat.ui.screen.chatlist.GlobalSearchScreen
import com.maodouchat.ui.screen.chatlist.NotificationCenterScreen
import com.maodouchat.ui.screen.contacts.MyQrCodeScreen
import com.maodouchat.ui.screen.contacts.ScanScreen
import com.maodouchat.ui.screen.explore.MomentsScreen
import com.maodouchat.ui.screen.settings.AboutScreen
import com.maodouchat.ui.screen.settings.AccountSecurityScreen
import com.maodouchat.ui.screen.settings.AiPrivacySettingsScreen
import com.maodouchat.ui.screen.settings.BlockedUsersScreen
import com.maodouchat.ui.screen.settings.GeneralSettingsScreen
import com.maodouchat.ui.screen.settings.ModerationScreen
import com.maodouchat.ui.screen.settings.MyReportsScreen
import com.maodouchat.ui.screen.settings.NotificationSettingsScreen
import com.maodouchat.ui.screen.settings.ServerSettingsScreen
import com.maodouchat.ui.screen.settings.ThemeEditorScreen
import com.maodouchat.ui.screen.settings.ThemeWorkbenchScreen
import com.maodouchat.ui.screen.settings.WatermarkForensicScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G334：**目的地级真机烟雾测试**——每个可导航到的页面，用**真实 Screen + 真实 `viewModel()`**
 * 组合一次，断言它渲染出了节点、且组合过程没有抛异常。
 *
 * 为什么要有这一层（它补的是「模拟器测试太草率」那个洞）：
 * - 语义测试断言的是「某个交互做了什么」，夹具里通常直接 new 被测类，**绕过真实装配**；
 * - 于是像 `ChatDetailDeps` 那种「构造函数顺序错了」的缺陷，真机一进去就闪退，测试却全绿；
 * - 逐页手动截图能发现，但不可持续、也不进 CI。
 *
 * 这一层刻意**只做冒烟**（能渲染 + 不崩），不做行为断言——行为的验证仍归各自的测试。
 * 它覆盖的是「页面能不能打开」这个最基础、也最容易在真机上被打脸的性质。
 *
 * 纪律：需要的会话/数据走真实 Room 播种（`@Before`），跑完清理（`@After`）。
 * 无会话时各页会进入「会话过期」等空态——那也算渲染成功，因为崩不崩与有没有数据无关。
 */
@RunWith(AndroidJUnit4::class)
class DestinationSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDatabase.getInstance(context)
    private val seededChatId = "smoke-chat-1"

    /** 任何语义节点的匹配器——用来断言「真的渲染出了东西」，而不是只看根节点。 */
    private fun anyNode() = SemanticsMatcher("any semantics node") { true }

    private fun smoke(name: String, content: @Composable () -> Unit) {
        compose.setContent { content() }
        compose.waitForIdle()
        val nodes = compose.onAllNodes(anyNode()).fetchSemanticsNodes()
        assertTrue("$name 组合后没有任何语义节点（页面没渲染出来）", nodes.size > 1)
    }

    @Before
    fun seed() = runBlocking {
        db.chatDao().deleteAllChats()
        db.chatDao().insertChats(
            listOf(ChatEntity(id = seededChatId, isGroup = false, lastMessage = "hi", lastMessageTime = 1_000L))
        )
    }

    @After
    fun clean() = runBlocking { db.chatDao().deleteAllChats() }

    // ── 设置族 ──

    @Test fun accountSecurity() = smoke("安全中心") { AccountSecurityScreen() }

    @Test fun generalSettings() = smoke("通用设置") { GeneralSettingsScreen() }

    @Test fun notificationSettings() = smoke("通知设置") { NotificationSettingsScreen() }

    @Test fun moderation() = smoke("审核与风控") { ModerationScreen() }

    @Test fun aiPrivacy() = smoke("AI 与隐私") { AiPrivacySettingsScreen() }

    @Test fun myReports() = smoke("我的举报") { MyReportsScreen() }

    @Test fun blockedUsers() = smoke("黑名单") { BlockedUsersScreen(onBack = {}) }

    @Test fun serverSettings() = smoke("服务器设置") { ServerSettingsScreen() }

    @Test fun about() = smoke("关于") { AboutScreen() }

    @Test fun themeEditor() = smoke("主题编辑器") { ThemeEditorScreen() }

    @Test fun themeWorkbench() = smoke("主题工作台") { ThemeWorkbenchScreen() }

    @Test fun watermarkForensic() = smoke("水印取证") { WatermarkForensicScreen(onBack = {}) }

    // ── 聊天/会话族（需要会话 id 的用播种的那条） ──

    @Test fun mediaCenter() = smoke("媒体中心") {
        MediaCenterScreen(onBack = {}, onOpenMessage = {})
    }

    @Test fun starredMessages() = smoke("星标消息") { StarredMessagesScreen(onBack = {}) }

    @Test fun aiTasks() = smoke("AI 任务") { AiTasksScreen(onBack = {}) }

    @Test fun globalSearch() = smoke("全局搜索") {
        GlobalSearchScreen(onBack = {}, onOpenResult = { _, _ -> })
    }

    @Test fun notificationCenter() = smoke("通知中心") { NotificationCenterScreen(onBack = {}) }

    @Test fun callHistory() = smoke("通话记录") {
        CallHistoryScreen(onBack = {}, onCall = { _, _, _ -> })
    }

    // ── 联系人/探索/AI 助手族 ──

    @Test fun myQrCode() = smoke("我的二维码") { MyQrCodeScreen() }

    @Test fun scan() = smoke("扫一扫") { ScanScreen() }

    @Test fun moments() = smoke("动态") { MomentsScreen() }

    @Test fun maodouAgent() = smoke("毛豆助手") { MaodouAgentScreen(onBack = {}) }
}
