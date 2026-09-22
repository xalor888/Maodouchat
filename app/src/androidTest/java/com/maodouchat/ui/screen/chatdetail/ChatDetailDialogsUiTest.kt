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

    // ---- DeleteMessageConfirmDialog：12 个里分叉最多的一个 ----

    private fun setDeleteDialog(
        isOwn: Boolean,
        isForwardable: Boolean,
        onDelete: () -> Unit = {},
        onForward: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            DeleteMessageConfirmDialog(
                visible = true,
                isOwn = isOwn,
                isForwardable = isForwardable,
                onDelete = onDelete,
                onForward = onForward,
                onDismiss = onDismiss,
            )
        }
    }

    private fun hasNode(text: String): Boolean =
        compose.onAllNodes(androidx.compose.ui.test.hasText(text))
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun deleteOwnMessageOffersDeleteAndForwardAndOnlyDeleteFires() {
        var delete = 0; var forward = 0; var dismiss = 0
        setDeleteDialog(isOwn = true, isForwardable = true, onDelete = { delete++ }, onForward = { forward++ }, onDismiss = { dismiss++ })

        // 自己的消息：自己的文案 + 红色删除 + 可转发
        compose.onNodeWithText(str(R.string.chat_delete_own_message)).assertIsDisplayed()
        check(hasNode(str(R.string.chat_forward))) { "isForwardable=true 时应有转发按钮" }
        compose.onNodeWithText(str(R.string.chat_delete)).performClick()
        compose.runOnIdle { check(delete == 1) { "点删除应触发 1 次 onDelete，实际 $delete" } }
        check(forward == 0 && dismiss == 0) { "点删除不应触发 onForward/onDismiss（f=$forward d=$dismiss）" }
    }

    @Test
    fun deleteOwnMessageWithoutForwardPermissionHidesTheForwardButton() {
        var delete = 0; var forward = 0
        setDeleteDialog(isOwn = true, isForwardable = false, onDelete = { delete++ }, onForward = { forward++ })

        check(hasNode(str(R.string.chat_delete))) { "删除按钮应在" }
        check(!hasNode(str(R.string.chat_forward))) { "isForwardable=false 时不该有转发按钮" }
        compose.onNodeWithText(str(R.string.chat_delete)).performClick()
        compose.runOnIdle { check(delete == 1) { "点删除应触发 onDelete" } }
        check(forward == 0) { "没有转发按钮就不该触发 onForward" }
    }

    @Test
    fun deleteSomeoneElsesMessageOnlyAcknowledges() {
        var delete = 0; var dismiss = 0
        setDeleteDialog(isOwn = false, isForwardable = true, onDelete = { delete++ }, onDismiss = { dismiss++ })

        // 别人的消息：文案换成「只能删除自己发送的消息」
        compose.onNodeWithText(str(R.string.chat_delete_other_message)).assertIsDisplayed()
        // 确认位是「知道了」，点它走 onDismiss 而不是 onDelete
        compose.onNodeWithText(str(R.string.chat_acknowledge)).performClick()
        compose.runOnIdle { check(dismiss == 1) { "点「知道了」应触发 onDismiss，实际 $dismiss" } }
        check(delete == 0) { "别人的消息不该能删（onDelete 被触发 $delete 次）" }
        // 别人的消息没有删除/转发/取消
        check(!hasNode(str(R.string.chat_delete))) { "别人的消息不该出现删除按钮" }
        check(!hasNode(str(R.string.chat_forward))) { "别人的消息不该出现转发按钮" }
    }

    @Test
    fun deleteDialogRendersNothingWhenNotVisible() {
        compose.setContent {
            DeleteMessageConfirmDialog(
                visible = false,
                isOwn = true,
                isForwardable = true,
                onDelete = {}, onForward = {}, onDismiss = {},
            )
        }
        check(!hasNode(str(R.string.chat_delete_message_title))) { "visible=false 时不该渲染标题" }
    }

    // ---- GroupCallTypeDialog：双按钮 + needsMemberPick 分支 ----

    private fun setGroupCallDialog(
        candidateCount: Int,
        visible: Boolean = true,
        onPick: (com.maodouchat.webrtc.CallType) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            GroupCallTypeDialog(
                visible = visible,
                candidateCount = candidateCount,
                onPick = onPick,
                onDismiss = onDismiss,
            )
        }
    }

    @Test
    fun groupCallUnderMeshLimitOmitsTheMemberPickerHint() {
        var dismiss = 0
        val picked = mutableListOf<com.maodouchat.webrtc.CallType>()
        val under = com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS - 1
        setGroupCallDialog(candidateCount = under, onPick = { picked += it }, onDismiss = { dismiss++ })

        // 未超限：只显示上限说明，**不**显示「需要选人」
        compose.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.call_group_mesh_limit, com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS)
        ).assertIsDisplayed()
        check(!hasNode(str(R.string.call_select_members_needed_hint))) { "未超限时不该出现选人提示" }

        // 点「语音通话」→ 只传 AUDIO 出来，不触发 onDismiss
        compose.onNodeWithText(str(R.string.chat_voice_call)).performClick()
        compose.runOnIdle {
            check(picked == listOf(com.maodouchat.webrtc.CallType.AUDIO)) { "应只收到 AUDIO，实际 $picked" }
        }
        check(dismiss == 0) { "点语音通话不应触发 onDismiss" }
    }

    @Test
    fun groupCallOverMeshLimitShowsTheHintAndStillOnlyReportsTheType() {
        val picked = mutableListOf<com.maodouchat.webrtc.CallType>()
        val over = com.maodouchat.webrtc.GroupCallPolicy.MAX_MESH_MEMBERS + 1
        setGroupCallDialog(candidateCount = over, onPick = { picked += it })

        // 超限：出现选人提示
        compose.onNodeWithText(str(R.string.call_select_members_needed_hint)).assertIsDisplayed()

        // 关键边界：composable 只负责把**类型**传出来；「直接起通话还是打开选人弹窗」
        // 是调用方的决定（它要摸 pendingGroupCallType / showGroupCallMemberDialog 等路由状态）。
        // 所以即便超限，点按钮也仍然只是 onPick(VIDEO)——不多做一件事。
        compose.onNodeWithText(str(R.string.chat_video_call)).performClick()
        compose.runOnIdle {
            check(picked == listOf(com.maodouchat.webrtc.CallType.VIDEO)) { "应只收到 VIDEO，实际 $picked" }
        }
    }

    @Test
    fun groupCallDialogRendersNothingWhenNotVisible() {
        setGroupCallDialog(candidateCount = 0, visible = false)
        check(!hasNode(str(R.string.chat_group_call))) { "visible=false 时不该渲染标题" }
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
