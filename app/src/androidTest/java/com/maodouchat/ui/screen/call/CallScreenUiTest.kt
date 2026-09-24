package com.maodouchat.ui.screen.call

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maodouchat.R
import com.maodouchat.webrtc.CallState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G315c：`CallScreen` 本体的 **UI 层**覆盖（Q03 第 1 项的第六个入口）。
 *
 * 与其他屏幕最大的不同：`CallScreen`（`CallScreen.kt:100-132`）**完全没有 viewModel 参数**，
 * 只收约 20 个纯数据参数加一串回调——所以连 fake VM 都不用造，
 * 直接 `setContent { CallScreen(contactName = ..., callState = ...) }`。
 *
 * 覆盖的 state → UI 映射（此前只有编译保证）：
 * - `isIncoming && callState == RINGING` → 同时出现「接听」与「挂断」；
 * - 非该组合 → **不出现**「接听」（只有挂断）；
 * - `callState == CONNECTED` → 显示通话时长文案；
 * - `errorMessage != null` → 出现「知道了」并触发 `onDismissError`。
 *
 * 最后两条是**屏幕级行为**：接听/挂断/知道了都是屏幕自己接的回调，
 * 行级 composable 测试碰不到这层接线。
 *
 * 纪律同 G301c/G309c：每例同时断言可见性与行为；文案一律 `R.string`。
 */
@RunWith(AndroidJUnit4::class)
class CallScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun str(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun acceptLabel() = str(R.string.call_accept)
    private fun hangUpLabel() = str(R.string.call_hang_up)

    // ---------- 可行性探针 ----------

    @Test
    fun callScreenRendersContactName() {
        compose.setContent {
            CallScreen(contactName = "alice", callState = CallState.CONNECTED)
        }
        compose.waitForIdle()
        compose.onNodeWithText("alice").assertIsDisplayed()
    }

    // ---------- state → UI：按钮集合 ----------

    @Test
    fun incomingRingingShowsBothAcceptAndHangUp() {
        compose.setContent {
            CallScreen(contactName = "bob", isIncoming = true, callState = CallState.RINGING)
        }
        compose.waitForIdle()

        // 两个按钮都必须真的渲染出来（它们是带 contentDescription 的 Icon）
        compose.onNodeWithContentDescription(acceptLabel()).assertIsDisplayed()
        compose.onNodeWithContentDescription(hangUpLabel()).assertIsDisplayed()
    }

    @Test
    fun outgoingCallShowsHangUpButNoAccept() {
        // 反向断言 + 条件渲染：非「来电响铃」时不得出现「接听」。
        // 若把 `if (isIncoming && callState == CallState.RINGING)` 改宽，这条会红。
        compose.setContent {
            CallScreen(contactName = "carol", isIncoming = false, callState = CallState.CALLING)
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(hangUpLabel()).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(acceptLabel()).assertCountEquals(0)
    }

    @Test
    fun connectedCallShowsItsDurationText() {
        // CONNECTED + AUDIO → 状态文案带时长（state → UI）
        compose.setContent {
            CallScreen(
                contactName = "dave",
                callType = com.maodouchat.webrtc.CallType.AUDIO,
                callState = CallState.CONNECTED,
                duration = "01:23",
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.call_audio_connected, "01:23")).assertIsDisplayed()
    }

    // ---------- 屏幕级行为 ----------

    @Test
    fun acceptButtonFiresTheScreensOnAcceptCallback() {
        var accepts = 0
        compose.setContent {
            CallScreen(
                contactName = "erin",
                isIncoming = true,
                callState = CallState.RINGING,
                onAccept = { accepts++ },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(acceptLabel()).performClick()
        assert(accepts == 1) { "点「接听」后 onAccept 回调应为 1，实际 $accepts" }
    }

    @Test
    fun hangUpButtonFiresTheScreensOnHangUpCallback() {
        var hangs = 0
        compose.setContent {
            CallScreen(
                contactName = "frank",
                isIncoming = true,
                callState = CallState.RINGING,
                onHangUp = { hangs++ },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(hangUpLabel()).performClick()
        assert(hangs == 1) { "点「挂断」后 onHangUp 回调应为 1，实际 $hangs" }
    }

    @Test
    fun errorMessageShowsAcknowledgeAndFiresDismiss() {
        // errorMessage 非空 → 出现「知道了」，点击触发 onDismissError（屏幕级行为）
        var dismisses = 0
        compose.setContent {
            CallScreen(
                contactName = "grace",
                callState = CallState.CALLING,
                errorMessage = "ice failed",
                onDismissError = { dismisses++ },
            )
        }
        compose.waitForIdle()

        val ack = str(R.string.chat_acknowledge)
        compose.onNodeWithText(ack).assertIsDisplayed()
        compose.onNodeWithText(ack).performClick()
        assert(dismisses == 1) { "点「知道了」后 onDismissError 回调应为 1，实际 $dismisses" }
    }

    @Test
    fun noErrorMessageMeansNoAcknowledgeButton() {
        compose.setContent {
            CallScreen(contactName = "heidi", callState = CallState.CALLING, errorMessage = null)
        }
        compose.waitForIdle()

        compose.onAllNodesWithText(str(R.string.chat_acknowledge)).assertCountEquals(0)
    }
}
