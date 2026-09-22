package com.maodouchat.ui.screen.chatdetail

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

/**
 * G173b：会话详情 dialog 的 **UI 层**覆盖（第一批）。
 *
 * 为什么需要它：G184–G162b 一共抽出 12 个 composable，此前全部只靠
 * **编译 + app JVM 单测 + 协议层 E2E** 验证。问题在于：
 * - app JVM 没有 Robolectric（依赖被注释掉），跑不了 Compose；
 * - E2E 驱动的是服务端往返，不渲染 UI。
 * 于是「确认按钮到底还在不在」「visible=false 时是不是真的不渲染」这类问题
 * **没有任何自动化手段能回答**——G192 的负控制只能证明「回调参数还被使用」，
 * 证不了「节点真的在屏幕上」。
 *
 * 这个文件补上那一层：用 `createComposeRule` 在真机/模拟器上真正渲染 dialog。
 * 每个断言都同时检查**可见性**与**行为**（点击是否触发回调）。
 *
 * 断言文案一律从 `R.string` 取，**不硬编码中文**——我第一版就硬编码错了
 * （把「发起密聊？」写成「开启密聊？」），那种测试会在模拟器上假红，
 * 而且每次文案微调都要改测试。
 */
@RunWith(AndroidJUnit4::class)
class ChatDetailDialogsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun secretChatConfirmShowsItsCopyAndFiresOnlyTheConfirmCallback() {
        var confirm = 0
        var dismiss = 0
        compose.setContent {
            SecretChatConfirmDialog(
                visible = true,
                onConfirm = { confirm++ },
                onDismiss = { dismiss++ },
            )
        }

        // 标题与正文必须真的渲染出来
        compose.onNodeWithText(str(R.string.secret_chat_confirm_enable_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.secret_chat_confirm_enable_body)).assertIsDisplayed()

        // 点「完成」→ 只触发 confirm，不触发 dismiss
        compose.onNodeWithText(str(R.string.common_done)).performClick()
        compose.runOnIdle { check(confirm == 1) { "点完成后 confirm 应为 1，实际 $confirm" } }
        check(dismiss == 0) { "点完成不应触发 dismiss，实际 $dismiss" }
    }

    @Test
    fun secretChatConfirmRendersNothingWhenNotVisible() {
        var confirm = 0
        compose.setContent {
            SecretChatConfirmDialog(
                visible = false,
                onConfirm = { confirm++ },
                onDismiss = {},
            )
        }
        // 不可见时一个节点都不该有；否则「visible 参数失效」这类回归抓不到。
        // 注意：onAllNodes/fetchSemanticsNodes 本身是同步调用，**不能**嵌在 runOnIdle {} 里
        // （我第一版那么写，报 "Functions that involve synchronization ... cannot be run
        // from the main thread"）。
        val nodes = compose.onAllNodes(
            androidx.compose.ui.test.hasText(str(R.string.secret_chat_confirm_enable_title))
        ).fetchSemanticsNodes()
        check(nodes.isEmpty()) { "visible=false 时仍渲染出了 ${nodes.size} 个标题节点" }
        check(confirm == 0) { "不可见时不应有任何交互" }
    }
}
