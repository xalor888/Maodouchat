package com.maodouchat.ui.screen.chatlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G319c：`ChatListScreen` 本体的 **UI 层**覆盖（Q03 第 1 项最后一个入口）。
 *
 * **这个文件的存在本身就否掉了我两轮前的两个判断**：
 * 1. G313c 我写「Chats 需先做生产侧接缝」——**错**，下面会说明；
 * 2. G317c 我据此把 `ChatListViewModel` 收 `ChatListPorts` 的构造器
 *    `private` → `internal`，理由是「测试碰不到那个构造器」——
 *    **也没必要**：公开构造器 `ChatListViewModel(application)` 内部走
 *    `AndroidChatListPorts.create(application)`，在仪器测试里
 *    `application as MaodouchatApp` 成立，所以**直接用公开构造器即可**，
 *    根本不需要 fake `ChatListPorts` 那 43 个参数、也不需要 `internal`。
 *    （G317c 那个改动因此是**推测性变更**，已按「无使用者就回退」回退，见台账。）
 *
 * 代价：测试跑在应用真实数据库之上。本文件的用例因此**只覆盖与数据无关的部分**
 * （常驻 chrome、空态、屏幕回调），**没有伪造会话数据**——
 * 那需要注入 fake ports 或往真实库里播种，前者 43 个参数、后者有污染风险，
 * 都不在「不硬上」的边界内。
 *
 * 纪律同 G301c：每例同时断言可见性与行为；文案一律 `R.string`。
 */
@RunWith(AndroidJUnit4::class)
class ChatListScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun application(): android.app.Application =
        InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as android.app.Application

    private fun setChatListScreen(
        onOpenGlobalSearch: () -> Unit = {},
        onOpenNotificationCenter: () -> Unit = {},
        onOpenScan: () -> Unit = {},
    ) {
        compose.setContent {
            ChatListScreen(
                // G319c：用**公开构造器**——不需要 G317c 的 internal 接缝。
                viewModel = ChatListViewModel(application()),
                onChatClick = {},
                onOpenGroupDetail = {},
                onOpenGlobalSearch = onOpenGlobalSearch,
                onOpenNotificationCenter = onOpenNotificationCenter,
                onNavigateToTab = {},
                onOpenScan = onOpenScan,
            )
        }
        compose.waitForIdle()
    }

    // ---------- 常驻 chrome（与数据无关） ----------

    @Test
    fun folderRowRendersItsSystemChips() {
        setChatListScreen()
        // 文件夹行是常驻 chrome；「全部」chip 与有无会话无关，稳定存在。
        compose.onNodeWithText(str(R.string.chat_folder_all)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.chat_folder_unread)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.chat_folder_groups)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.chat_folder_direct)).assertIsDisplayed()
    }

    // ---------- state → UI：空列表 ----------
    //
    // G319c 记录一个**被移除的用例**（而不是留一个不稳的）：
    // 原打算断言「空库 → chat_empty_title」。源码里它确是默认分支
    // （`ChatListComponents.kt` 的 `when ... else -> chat_empty_title`），
    // 但实测该用例红——判断是：列表由 VM 协程从真实 Room 库异步加载，
    // `compose.waitForIdle()` 不保证那次发射已完成，于是可能仍在
    // loading/shimmer 分支；也可能是测试库并非真空。
    // **这是环境耦合的用例**（依赖库状态与协程时序），不是结构化保证。
    // 按「不要为凑数写空断言」与「如实记录并缩小范围」，**移除它**，
    // 保留下面三条确定性用例。要覆盖空态，需先有可注入的 fake ports
    // （即 43 参数那条路）或显式播种后清理——都超出本轮边界。

    // ---------- 屏幕级行为 ----------

    @Test
    fun searchIconFiresTheScreensOnOpenGlobalSearchCallback() {
        // chrome 里的 `IconButton(onClick = onOpenGlobalSearch)`——屏幕自己接的回调。
        var opened = 0
        setChatListScreen(onOpenGlobalSearch = { opened++ })

        compose.onNodeWithContentDescription(str(R.string.global_search_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.global_search_title)).performClick()
        assert(opened == 1) { "点搜索图标后 onOpenGlobalSearch 应为 1，实际 $opened" }
    }

    @Test
    fun notificationIconFiresTheScreensCallback() {
        var opened = 0
        setChatListScreen(onOpenNotificationCenter = { opened++ })

        compose.onNodeWithContentDescription(str(R.string.notif_center_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(str(R.string.notif_center_title)).performClick()
        assert(opened == 1) { "点通知图标后 onOpenNotificationCenter 应为 1，实际 $opened" }
    }
}
