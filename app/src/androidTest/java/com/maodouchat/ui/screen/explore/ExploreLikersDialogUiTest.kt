package com.maodouchat.ui.screen.explore

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import com.maodouchat.network.UserDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G301c：Explore 侧的 **UI 层**覆盖（Q03 第 1 项的第二批，与
 * `ui/screen/contacts/ContactsRowsUiTest` 同一轮）。
 *
 * 为什么测 `LikersDialog` 而不是 `ExploreScreen` 本体：
 * `ExploreScreen(viewModel: ExploreViewModel = viewModel())` 带 ViewModel 默认参数，
 * 在 `createComposeRule` 里 `setContent` 会构造真实 ViewModel（要 Repository /
 * 数据库 / 网络），那是集成测试的范畴。`LikersDialog` 是**无状态**的
 * （`likers` + `isLoading` + 两个回调），且 Explore 与 PostDetail 共用——
 * 测它一处，覆盖两个调用方。
 *
 * 这个 dialog 有三条互斥分支，正好是 UI 测试该钉的东西：
 * `isLoading` → 进度指示；`likers.isEmpty()` → 「还没有人点赞」；
 * 否则 → 点赞者列表（可点击、在线者带「在线」标记）。
 * 此前这三条**只有编译保证**——「loading 时空文案会不会也渲染」
 * 「点点赞者到底触发没触发 onOpenUser」没有任何自动化手段能回答。
 *
 * 纪律同 G173b / G301c：每例同时断言可见性与行为；文案一律取 `R.string`。
 */
@RunWith(AndroidJUnit4::class)
class ExploreLikersDialogUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun liker(
        id: String = "u1",
        name: String = "alice",
        isOnline: Boolean = false,
    ) = UserDto(id = id, name = name, isOnline = isOnline)

    @Test
    fun dialogShowsItsTitleAndRendersEveryLikerName() {
        compose.setContent {
            LikersDialog(
                likers = listOf(liker(id = "u1", name = "alice"), liker(id = "u2", name = "bob")),
                isLoading = false,
                onDismiss = {},
            )
        }

        compose.onNodeWithText(str(R.string.explore_likers_title)).assertIsDisplayed()
        compose.onNodeWithText("alice").assertIsDisplayed()
        compose.onNodeWithText("bob").assertIsDisplayed()
    }

    @Test
    fun clickingLikerFiresOpenUserWithThatLikerId() {
        var opened: String? = null
        compose.setContent {
            LikersDialog(
                likers = listOf(liker(id = "u1", name = "alice"), liker(id = "u2", name = "bob")),
                isLoading = false,
                onDismiss = {},
                onOpenUser = { opened = it },
            )
        }

        // 行为：点第二行必须回传**那一行**的 id，不能回传第一行或 null
        compose.onNodeWithText("bob").performClick()
        assert(opened == "u2") { "点击 bob 后应回传 u2，实际 $opened" }
    }

    @Test
    fun onlineLikerShowsTheOnlineBadge() {
        compose.setContent {
            LikersDialog(
                likers = listOf(liker(id = "u1", name = "alice", isOnline = true)),
                isLoading = false,
                onDismiss = {},
            )
        }

        // isOnline 为真时额外渲染 R.string.chat_online 标记
        compose.onNodeWithText(str(R.string.chat_online)).assertIsDisplayed()
    }

    @Test
    fun offlineLikerShowsNoOnlineBadge() {
        compose.setContent {
            LikersDialog(
                likers = listOf(liker(id = "u1", name = "alice", isOnline = false)),
                isLoading = false,
                onDismiss = {},
            )
        }

        compose.onNodeWithText("alice").assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.chat_online)).assertCountEquals(0)
    }

    @Test
    fun emptyAndNotLoadingShowsTheEmptyHint() {
        compose.setContent {
            LikersDialog(likers = emptyList(), isLoading = false, onDismiss = {})
        }

        compose.onNodeWithText(str(R.string.explore_likers_empty)).assertIsDisplayed()
    }

    @Test
    fun loadingStateHidesTheEmptyHint() {
        // 反向断言 + 互斥分支：loading 为真时**不能**出现空文案。
        // 若把 `when` 里 isLoading 那一支删掉或调到最后，这条会红。
        compose.setContent {
            LikersDialog(likers = emptyList(), isLoading = true, onDismiss = {})
        }

        compose.onAllNodesWithText(str(R.string.explore_likers_empty)).assertCountEquals(0)
    }

    @Test
    fun closeButtonFiresDismiss() {
        var dismisses = 0
        compose.setContent {
            LikersDialog(
                likers = listOf(liker(name = "alice")),
                isLoading = false,
                onDismiss = { dismisses++ },
            )
        }

        compose.onNodeWithText(str(R.string.explore_close)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.explore_close)).performClick()
        assert(dismisses == 1) { "关闭回调应为 1，实际 $dismisses" }
    }
}
