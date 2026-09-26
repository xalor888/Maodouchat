package com.maodouchat.server.common

import com.maodouchat.server.repository.ConversationQueryRepository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G188：`CallSignalingValidators` 的前两个纯函数 + `isValidGroupSignalMetadata` 的**不触库分支**。
 *
 * 生产代码注释点名了三个"容易被顺手改好"的语义，本文件就是钉死它们的可执行版本：
 * - 信令类型是**小写连字符**形状（`offer` / `ice-candidate` / `hang-up`…），
 *   写成 `CALL_OFFER` 或 `ICE_CANDIDATE` 必须判非法——那等于把线上每一通真实通话判死；
 * - 载荷只做「非终端类型必须非空 + 长度上限」，**不做 SDP 结构校验**：
 *   一个明显不是 SDP 的字符串照样放行，在这里收紧格式就是凭空改协议；
 * - `callId` 上限是 **100**，不是 64。
 *
 * 不覆盖：`isValidGroupSignalMetadata` 中需要 `getById` 查库的最后三段
 * （chat 不存在 / 非群 / 成员不属该群）。`ConversationQueryRepository` 是 final class
 * 且本模块无 mock 依赖，纯单测无法替换它；那三段属于路由/DB 级测试的领地。
 * 传进来的 repository 实例在本文件所有用例里都不会被调用（各分支都在查库前返回）。
 */
class CallSignalingValidatorsTest {

    private val unusedRepo = ConversationQueryRepository()

    private fun groupMeta(
        groupId: String = "g1",
        members: List<String> = listOf("u1", "u2"),
        invite: Boolean = false,
        callId: String = "call-1",
        from: String = "u1",
        to: String = "u2",
    ) = CallSignalingValidators.isValidGroupSignalMetadata(
        groupId = groupId,
        groupMemberIds = members,
        groupInvite = invite,
        callId = callId,
        fromUserId = from,
        toUserId = to,
        conversationQueryRepository = unusedRepo,
    )

    // ---- isValidSignalPayload：线上形状的小写 type ----

    @Test
    fun `all six client signaling types are accepted`() {
        for (type in listOf("offer", "answer", "ice-candidate", "hang-up", "busy", "reject")) {
            assertTrue(
                CallSignalingValidators.isValidSignalPayload(type, "payload"),
                "$type 是客户端实际在发的形状，必须放行",
            )
        }
    }

    @Test
    fun `enum-style or spaced type names are rejected`() {
        // 这几个是"顺手规范化"最可能写出来的样子；一旦 ALLOWED 集合被改，全线通话即挂
        for (type in listOf("CALL_OFFER", "Offer", "offer ", "iceCandidate", "ice candidate", "hangup", "")) {
            assertFalse(CallSignalingValidators.isValidSignalPayload(type, "payload"), "$type 不在协议形状内")
        }
    }

    @Test
    fun `terminal types tolerate blank payload but offer answer ice-candidate do not`() {
        for (terminal in listOf("hang-up", "busy", "reject")) {
            assertTrue(CallSignalingValidators.isValidSignalPayload(terminal, ""), "$terminal 是终止信令，允许空载荷")
            assertTrue(CallSignalingValidators.isValidSignalPayload(terminal, "   "))
        }
        for (required in listOf("offer", "answer", "ice-candidate")) {
            assertFalse(CallSignalingValidators.isValidSignalPayload(required, ""), "$required 空载荷必须拒")
            assertFalse(CallSignalingValidators.isValidSignalPayload(required, "  \n\t "))
        }
    }

    @Test
    fun `payload length limit is exclusive above 32768`() {
        assertTrue(CallSignalingValidators.isValidSignalPayload("offer", "x".repeat(32_768)), "32768 含内合法")
        assertFalse(CallSignalingValidators.isValidSignalPayload("offer", "x".repeat(32_769)), "超一个字符即拒")
    }

    // ---- isValidCallId：上限 100，字符集 [A-Za-z0-9_-] ----

    @Test
    fun `callId accepts the documented charset and the 100-char boundary`() {
        assertTrue(CallSignalingValidators.isValidCallId("call-1_A-b"))
        assertTrue(CallSignalingValidators.isValidCallId("c".repeat(100)), "上限是 100，不是 64")
        assertFalse(CallSignalingValidators.isValidCallId("c".repeat(101)))
        assertFalse(CallSignalingValidators.isValidCallId(""), "空 session id 会让挂断/清理误伤无关会话")
        assertFalse(CallSignalingValidators.isValidCallId("   "), "isBlank 单独挡不住正则，但先被 blank 检查拒")
    }

    @Test
    fun `callId rejects characters outside the web-safe set`() {
        for (bad in listOf("call 1", "call/1", "call+1", "call=1", "call.1", "call*1", "ca.ll", "../etc")) {
            assertFalse(CallSignalingValidators.isValidCallId(bad), "$bad 不匹配 ^[A-Za-z0-9_-]{1,100}$")
        }
    }

    @Test
    fun `callId regex is anchored - multiline suffix does not slip through`() {
        // Kotlin Regex.matches 本就要求整段匹配；这条钉住它不被改成 contains/find
        assertFalse(CallSignalingValidators.isValidCallId("ok\nx"))
        assertFalse(CallSignalingValidators.isValidCallId("\tok"))
        assertFalse(CallSignalingValidators.isValidCallId("ok" + "!".repeat(5)))
    }

    // ---- isValidGroupSignalMetadata：查库前的守卫分支 ----

    @Test
    fun `blank groupId is only legal for a bare non-group 1to1 signal`() {
        assertTrue(groupMeta(groupId = "", members = emptyList(), invite = false), "groupId 空 + 无成员 + 非邀请 = 1:1")
        assertFalse(groupMeta(groupId = "", members = emptyList(), invite = true), "邀请态要求有群")
        assertFalse(groupMeta(groupId = "", members = listOf("u1", "u2")), "groupId 空却带成员列表是矛盾输入")
    }

    @Test
    fun `group metadata requires a non-blank callId before anything else`() {
        assertFalse(groupMeta(callId = ""))
        assertFalse(groupMeta(callId = "  "))
    }

    @Test
    fun `groupId must match the same web-safe charset as callId`() {
        assertFalse(groupMeta(groupId = "g 1"))
        assertFalse(groupMeta(groupId = "g/1"))
    }

    @Test
    fun `mesh size bounds are enforced before any db lookup`() {
        assertFalse(groupMeta(members = listOf("u1")), "1 人不构成 mesh 群通话")
        val sixPlusOne = (1..7).map { "u$it" }
        assertFalse(groupMeta(members = sixPlusOne, from = "u1", to = "u7"), "7 人越界")
        // 2 与 6 是合法边界，但它们过了守卫就会走 getById（查库），不在纯单测领地内。
    }

    @Test
    fun `duplicate members are rejected rather than silently deduped`() {
        assertFalse(groupMeta(members = listOf("u1", "u1", "u2"), from = "u1", to = "u2"))
    }

    @Test
    fun `both endpoints must be in the declared member list`() {
        assertFalse(groupMeta(members = listOf("u1", "u2"), from = "u3", to = "u2"), "发起方不在名单")
        assertFalse(groupMeta(members = listOf("u1", "u2"), from = "u1", to = "u9"), "接收方不在名单")
    }
}
