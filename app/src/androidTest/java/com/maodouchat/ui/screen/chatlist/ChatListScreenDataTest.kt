package com.maodouchat.ui.screen.chatlist

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.entity.ChatEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G321c：`ChatListScreen` 的**数据相关路径**覆盖，补上 G319c 记录的深度缺口。
 *
 * 为什么 G319c 没做：那轮只覆盖了与数据无关的 chrome 与会调，
 * 因为「列表由 VM 协程从真实 Room 库异步加载，`waitForIdle()` 不保证发射完成」，
 * 空态用例因此红掉只能移除。
 *
 * 本轮换的两件事：
 * 1. **播种**——`ChatDao.insertChats(...)` 往真实库塞一个群聊，
 *    `@After` 用 `deleteAllChats()` 清干净（播种必须可清理）。
 *    这不需要 fake `ChatListPorts`（那有 43 个参数），也不需要任何生产接缝。
 * 2. **等而不是等 Idle**——用 `compose.waitUntil { 节点出现 }`，
 *    因为 VM 的加载是协程里异步的，`waitForIdle()` 只是等 Compose 空闲，
 *    不保证那次库查询已发射。**这是 G319c 那条红用例的根因。**
 *
 * 群聊标题直接取 `groupName`（`ChatListComponents.kt:321`），
 * 所以播种一个带 `groupName` 的群聊就能确定性断言，不必 join `userDao`。
 *
 * 纪律同 G301c：每例同时断言可见性与行为；文案一律 `R.string`。
 */
@RunWith(AndroidJUnit4::class)
class ChatListScreenDataTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = AppDatabase.getInstance(context)

    /** 文案一律取 R.string，不硬编码中文。 */
    private fun str(id: Int): String = context.getString(id)

    private val seededName = "播种群"
    private val seededLastMessage = "第一条消息"
    private val seededChatId = "seed-group-1"

    @Before
    fun seedGroupChat() = runBlocking {
        db.chatDao().deleteAllChats()
        db.chatDao().insertChats(
            listOf(
                ChatEntity(
                    id = seededChatId,
                    isGroup = true,
                    groupName = seededName,
                    lastMessage = seededLastMessage,
                    lastMessageTime = 1_000L,
                )
            )
        )
    }

    @After
    fun cleanLibrary() = runBlocking {
        // 播种必须可清理：绝不把脏数据留给同进程其它测试。
        db.chatDao().deleteAllChats()
    }

    private fun setChatListScreen(
        onChatClick: (String) -> Unit = {},
        onOpenMediaCenter: (String) -> Unit = {},
    ) {
        compose.setContent {
            ChatListScreen(
                // 公开构造器 + 真实库：不需要 G317c 那个 internal 接缝（已回退）。
                viewModel = ChatListViewModel(context.applicationContext as android.app.Application),
                onChatClick = onChatClick,
                onOpenGroupDetail = {},
                onOpenGlobalSearch = {},
                onOpenNotificationCenter = {},
                onNavigateToTab = {},
                onOpenScan = {},
                onOpenMediaCenter = onOpenMediaCenter,
            )
        }
        compose.waitForIdle()
    }

    /** 等到库里那条会话真的渲染出来——不用 sleep，等节点出现为止。 */
    private fun awaitSeededChat() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(seededName).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 长按那条会话，再等菜单真的弹出来。 */
    private fun longPressSeededChatAndAwaitMenu() {
        awaitSeededChat()
        compose.onNodeWithText(seededName).performTouchInput { longClick() }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(str(R.string.chat_view_shared_media))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ---------- data → UI ----------

    @Test
    fun seededGroupChatRendersItsNameAndLastMessage() {
        setChatListScreen()
        awaitSeededChat()

        compose.onNodeWithText(seededName).assertExists()
        // 副标题是播种的 lastMessage——这条把「库数据流到 UI」整体钉住。
        compose.onAllNodesWithText(seededLastMessage).fetchSemanticsNodes().let {
            assert(it.isNotEmpty()) { "播种的 lastMessage「$seededLastMessage」没有渲染出来" }
        }
    }

    // ---------- 屏幕级行为 ----------

    @Test
    fun clickingTheSeededChatFiresOnChatClickWithItsId() {
        var clicked: String? = null
        setChatListScreen(onChatClick = { clicked = it })
        awaitSeededChat()

        compose.onNodeWithText(seededName).performClick()
        assert(clicked == seededChatId) {
            "点会话后 onChatClick 应回传 $seededChatId，实际 $clicked"
        }
    }

    // ---------- 长按菜单（G323c） ----------

    @Test
    fun longPressOpensTheContextMenuWithItsItems() {
        // 长按那条会话 → 菜单必须真的弹出来（这里有可见性 + 时序两层）：
        // DropdownMenu 是异步出现的，所以用 waitUntil 等菜单项，不用 sleep。
        setChatListScreen()
        longPressSeededChatAndAwaitMenu()

        compose.onNodeWithText(str(R.string.chat_view_shared_media)).assertExists()
    }

    @Test
    fun menuItemFiresOnOpenMediaCenterWithTheChatId() {
        // 菜单项 `onClick = { onOpenMediaCenter(chat.id); ... }`——屏幕自己接的回调，
        // 且必须回传**长按的那条** chatId。这是行级 composable 测试碰不到的链路。
        var opened: String? = null
        setChatListScreen(onOpenMediaCenter = { opened = it })
        longPressSeededChatAndAwaitMenu()

        compose.onNodeWithText(str(R.string.chat_view_shared_media)).performClick()
        assert(opened == seededChatId) {
            "点「查看共享媒体」后 onOpenMediaCenter 应回传 $seededChatId，实际 $opened"
        }
    }
}
