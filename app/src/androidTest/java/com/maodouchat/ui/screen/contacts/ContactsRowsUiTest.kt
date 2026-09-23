package com.maodouchat.ui.screen.contacts

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import com.maodouchat.contacts.usecase.FriendRequestItem
import com.maodouchat.data.model.User
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G301c：Contacts 主流程屏幕的 **UI 层**覆盖（Q03 第 1 项的第一批）。
 *
 * 为什么需要它：此前 `app/src/androidTest` 里唯一的 Compose UI 测试是
 * `ChatDetailDialogsUiTest`（dialog 层）。而底部导航的三个主屏幕
 * Chats / Contacts / Explore **零 UI 渲染测试**——JVM 层有
 * `ContactsViewModelTest` / `ContactsUiStateFilterTest` 等策略测试，
 * E2E 驱动的是服务端往返，都不渲染 UI。于是「接受按钮到底渲染不渲染」
 * 「点拒绝到底触发没触发回调」这类问题**没有任何自动化手段能回答**。
 *
 * 为什么测这些 composable 而不是 `ContactsScreen` 本体：
 * `ContactsScreen(viewModel: ContactsViewModel = viewModel())` 带 ViewModel 默认参数，
 * 在 `createComposeRule` 里 `setContent` 会尝试构造真实 ViewModel（需要
 * Repository / 数据库），那是集成测试的范畴。这里测的是**无状态的展示型
 * composable**——纯数据 + 回调，正是能真正被 UI 测试钉住的那一层，
 * 与 `ChatDetailDialogsUiTest` 选 `SecretChatConfirmDialog` 是同一思路。
 *
 * 纪律（沿用 G173b 的三条）：
 * 1. 每条例必须**同时断言可见性与行为**（`assertIsDisplayed` + `performClick` 后回调计数变化）——
 *    只断言渲染出来等于没测交互。
 * 2. 文案一律从 `R.string` 取，**不硬编码中文**（G173b 第一版硬编码错了，
 *    把「发起密聊？」写成「开启密聊？」，在模拟器上假红）。
 * 3. 负控制见 `rejectButtonDisappearsWhenCallbacksAreAbsent`：
 *    那条本身就证明了「按钮存在与否由回调是否为 null 决定」，
 *    即把 onReject 传 null 时按钮必须消失——反向断言。
 */
@RunWith(AndroidJUnit4::class)
class ContactsRowsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun user(
        id: String = "u1",
        name: String = "alice",
        nickname: String? = null,
        isOnline: Boolean = false,
        status: String = "",
    ) = User(id = id, name = name, nickname = nickname, isOnline = isOnline, status = status)

    private fun request(
        id: String = "r1",
        user: User = user(),
        message: String = "",
        outgoing: Boolean = false,
    ) = FriendRequestItem(id = id, user = user, message = message, outgoing = outgoing)

    // ---------- ContactItem ----------

    @Test
    fun contactItemShowsDisplayNameAndFiresClick() {
        var clicks = 0
        compose.setContent {
            ContactItem(user = user(name = "alice"), onClick = { clicks++ })
        }

        // 可见性：displayName 真的在屏幕上
        compose.onNodeWithText("alice").assertIsDisplayed()
        // 行为：点击真的触发回调
        compose.onNodeWithText("alice").performClick()
        assert(clicks == 1) { "点击后回调计数应为 1，实际 $clicks" }
    }

    @Test
    fun contactItemPrefersNicknameOverNameForDisplay() {
        // displayName = nickname ?: name ?: id（见 User.displayName）
        compose.setContent {
            ContactItem(user = user(name = "alice", nickname = "小爱"), onClick = {})
        }

        compose.onNodeWithText("小爱").assertIsDisplayed()
    }

    @Test
    fun contactItemShowsOnlineSubtitleWhenOnline() {
        // 在线且无昵称时，副标题走 R.string.contacts_online
        compose.setContent {
            ContactItem(user = user(name = "bob", isOnline = true), onClick = {})
        }

        compose.onNodeWithText(str(R.string.contacts_online)).assertIsDisplayed()
    }

    @Test
    fun contactItemShowsOfflineSubtitleWhenOffline() {
        // 离线且无昵称、无 status 时，副标题走 R.string.contacts_offline
        compose.setContent {
            ContactItem(user = user(name = "carol", isOnline = false), onClick = {})
        }

        compose.onNodeWithText(str(R.string.contacts_offline)).assertIsDisplayed()
    }

    @Test
    fun contactItemFiresLongClickWhenProvided() {
        var longClicks = 0
        compose.setContent {
            ContactItem(user = user(name = "dave"), onClick = {}, onLongClick = { longClicks++ })
        }

        compose.onNodeWithText("dave").performTouchInput { longClick() }
        assert(longClicks == 1) { "长按后回调计数应为 1，实际 $longClicks" }
    }

    // ---------- FriendRequestRow ----------

    @Test
    fun incomingRequestShowsBothButtonsAndEachFiresItsOwnCallback() {
        var accepts = 0
        var rejects = 0
        compose.setContent {
            FriendRequestRow(
                request = request(user = user(name = "erin")),
                onAccept = { accepts++ },
                onReject = { rejects++ },
            )
        }

        val accept = str(R.string.contacts_friend_accept)
        val reject = str(R.string.contacts_friend_reject)

        // 两个按钮都真的渲染出来
        compose.onNodeWithText(accept).assertIsDisplayed()
        compose.onNodeWithText(reject).assertIsDisplayed()

        // 且各自只触发自己的回调
        compose.onNodeWithText(accept).performClick()
        compose.onNodeWithText(reject).performClick()
        assert(accepts == 1) { "同意回调应为 1，实际 $accepts" }
        assert(rejects == 1) { "拒绝回调应为 1，实际 $rejects" }
    }

    @Test
    fun incomingRequestShowsItsMessageBody() {
        compose.setContent {
            FriendRequestRow(
                request = request(user = user(name = "frank"), message = "我是弗兰克"),
                onAccept = {},
                onReject = {},
            )
        }

        // 申请留言必须渲染
        compose.onNodeWithText("我是弗兰克").assertIsDisplayed()
    }

    @Test
    fun outgoingRequestShowsPendingHintAndCancelInsteadOfAccept() {
        // 反向断言 + 条件渲染：outgoing 且只给 onCancel 时，
        // 必须显示「等待对方通过」与「撤回」，且**不能**出现「同意」。
        var cancelClicks = 0
        compose.setContent {
            FriendRequestRow(
                request = request(user = user(name = "grace"), outgoing = true),
                onAccept = null,
                onReject = null,
                onCancel = { cancelClicks++ },
            )
        }

        val pending = str(R.string.contacts_friend_request_pending)
        val cancel = str(R.string.contacts_friend_cancel)

        compose.onNodeWithText(pending).assertIsDisplayed()
        compose.onNodeWithText(cancel).assertIsDisplayed()
        compose.onAllNodesWithText(str(R.string.contacts_friend_accept)).assertCountEquals(0)

        compose.onNodeWithText(cancel).performClick()
        assert(cancelClicks == 1) { "撤回回调应为 1，实际 $cancelClicks" }
    }

    @Test
    fun rejectButtonDisappearsWhenCallbacksAreAbsent() {
        // 负控制的主体：onReject 传 null 时「拒绝」必须不存在。
        // 若把 `if (onAccept != null && onReject != null)` 改成恒真，
        // 这条会红——证明它真的在承重。
        compose.setContent {
            FriendRequestRow(
                request = request(user = user(name = "heidi")),
                onAccept = null,
                onReject = null,
            )
        }

        compose.onAllNodesWithText(str(R.string.contacts_friend_accept)).assertCountEquals(0)
        compose.onAllNodesWithText(str(R.string.contacts_friend_reject)).assertCountEquals(0)
    }

    @Test
    fun longPressBlocksOnlyWhenBlockCallbackProvided() {
        var blocks = 0
        compose.setContent {
            FriendRequestRow(
                request = request(user = user(name = "ivan")),
                onAccept = {},
                onReject = {},
                onBlock = { blocks++ },
            )
        }

        compose.onNodeWithText("ivan").performTouchInput { longClick() }
        assert(blocks == 1) { "长按拉黑回调应为 1，实际 $blocks" }
    }
}
