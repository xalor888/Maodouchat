package com.maodouchat.ui.screen.chatdetail.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G152：`groupAuditActionSearchTokens` 的测试。
 *
 * 群审计列表按动作给出一组「中英文关键词」，用户搜任意一个词就应命中该类动作。
 * 这里守两条：
 * 1. **每个已知动作都要有词**——漏一个分支会让那一类审计记录搜不到；
 * 2. **未知动作要有兜底**——服务端新增动作类型时不能返回空列表（否则搜索彻底失效）。
 */
class GroupAuditActionSearchTokensTest {

    private val known = mapOf(
        "MEMBER_ADDED" to listOf("添加", "成员", "added", "member", "add"),
        "MEMBER_JOINED" to listOf("加入", "邀请", "joined", "invite", "join"),
        "MEMBER_REMOVED" to listOf("移除", "踢出", "removed", "remove", "kick"),
        "MEMBER_PROMOTED" to listOf("管理员", "提升", "admin", "promoted", "promote"),
        "MEMBER_DEMOTED" to listOf("取消管理员", "降级", "demoted", "demote"),
        "MEMBER_MUTED" to listOf("禁言", "muted", "mute"),
        "MEMBER_UNMUTED" to listOf("解禁", "解除禁言", "unmuted", "unmute"),
        "GROUP_RENAMED" to listOf("群名", "改名", "rename", "renamed"),
        "ANNOUNCEMENT_UPDATED" to listOf("公告", "announcement"),
        "AVATAR_UPDATED" to listOf("头像", "avatar"),
        "INVITE_ROTATED" to listOf("邀请", "链接", "invite", "link"),
        "INVITE_CONFIGURED" to listOf("邀请", "链接", "invite", "link"),
        "TITLE_UPDATED" to listOf("头衔", "title"),
        "NICKNAME_UPDATED" to listOf("昵称", "nickname"),
        "MEMBER_LEFT" to listOf("退群", "退出", "left", "leave"),
        "OWNERSHIP_TRANSFERRED" to listOf("转让", "群主", "owner", "transfer"),
    )

    @Test
    fun `every known action maps to its exact token list`() {
        known.forEach { (action, expected) ->
            assertEquals("动作 $action 的关键词被改坏了", expected, groupAuditActionSearchTokens(action))
        }
    }

    @Test
    fun `every known action has at least one chinese and one ascii token`() {
        known.forEach { (action, tokens) ->
            assertTrue("$action 没有中文关键词", tokens.any { it.any { c -> c.code > 0x2E80 } })
            assertTrue("$action 没有英文关键词", tokens.any { it.all { c -> c.code < 0x2E80 } })
        }
    }

    @Test
    fun `invite rotated and configured share the same tokens`() {
        assertEquals(
            groupAuditActionSearchTokens("INVITE_ROTATED"),
            groupAuditActionSearchTokens("INVITE_CONFIGURED")
        )
    }

    @Test
    fun `unknown action falls back to a generic token list`() {
        val fallback = listOf("操作", "activity", "group")
        assertEquals(fallback, groupAuditActionSearchTokens("SOMETHING_NEW"))
        assertEquals(fallback, groupAuditActionSearchTokens(""))
        // 大小写敏感：小写不走已知分支
        assertEquals(fallback, groupAuditActionSearchTokens("member_added"))
    }

    @Test
    fun `no known action returns an empty list`() {
        (known.keys + listOf("UNKNOWN", "")).forEach { action ->
            assertTrue("$action 返回空列表", groupAuditActionSearchTokens(action).isNotEmpty())
        }
    }
}
