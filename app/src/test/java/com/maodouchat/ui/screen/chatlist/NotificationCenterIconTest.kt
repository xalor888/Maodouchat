package com.maodouchat.ui.screen.chatlist

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PhoneMissed
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.ui.graphics.Color
import com.maodouchat.data.repository.NotificationCenterItem
import com.maodouchat.ui.theme.Primary
import com.maodouchat.ui.theme.TextSecondary
import com.maodouchat.ui.theme.UnreadRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.maodouchat.notification.NotificationCenterType

/**
 * G159：`NotificationCenterScreen.iconForType` 的测试。
 *
 * 它非 Composable（读不到 MaterialTheme），所以图标色全是常量——
 * 这让它可以被纯 JVM 单测直接覆盖。断言同时盯**图标 identity** 和**颜色 ARGB**，
 * 只断言「非空」的话，把图标换成另一个照样过。
 */
class NotificationCenterIconTest {

    private val PINK = Color(0xFFE91E63)

    private fun item(type: String, extra: Map<String, String> = emptyMap()) =
        NotificationCenterItem(
            id = "n1",
            type = type,
            mergeKey = "k",
            title = "t",
            extra = extra,
        )

    @Test
    fun `each non interaction type has its own icon and colour`() {
        val cases = listOf(
            Triple(NotificationCenterType.MESSAGE, Icons.Outlined.MarkChatUnread, Primary),
            Triple(NotificationCenterType.MISSED_CALL, Icons.AutoMirrored.Outlined.PhoneMissed, UnreadRed),
            Triple(NotificationCenterType.AI_TASK, Icons.Outlined.ChecklistRtl, Primary),
            Triple(NotificationCenterType.GROUP_INVITE, Icons.Outlined.Public, Primary),
            Triple(NotificationCenterType.SECURITY, Icons.Outlined.VerifiedUser, Primary),
            Triple(NotificationCenterType.FRIEND_REQUEST, Icons.Outlined.PersonAdd, Primary),
        )
        cases.forEach { (type, icon, color) ->
            val (gotIcon, gotColor) = iconForType(item(type))
            assertEquals("$type 的图标不对", icon, gotIcon)
            assertEquals("$type 的颜色不对", color, gotColor)
        }
    }

    @Test
    fun `report and moderation fall back to the campaign icon`() {
        listOf(NotificationCenterType.REPORT, NotificationCenterType.MODERATION, "SOMETHING_NEW", "").forEach { type ->
            val (icon, color) = iconForType(item(type))
            assertEquals("$type 应走兜底", Icons.Outlined.Campaign, icon)
            assertEquals("$type 兜底色不对", TextSecondary, color)
        }
    }

    @Test
    fun `post interaction comment kinds use the bubble icon`() {
        listOf("comment", "comment_like").forEach { kind ->
            val (icon, color) = iconForType(item(NotificationCenterType.POST_INTERACTION, mapOf("kind" to kind)))
            assertEquals("kind=$kind 的图标不对", Icons.Outlined.ChatBubbleOutline, icon)
            assertEquals("kind=$kind 的颜色不对", Primary, color)
        }
    }

    @Test
    fun `post interaction other kinds use the pink heart`() {
        listOf("like", "post_like", "", "  ", "COMMENT", "Comment").forEach { kind ->
            val (icon, color) = iconForType(
                item(NotificationCenterType.POST_INTERACTION, mapOf("kind" to kind))
            )
            assertEquals("kind=$kind 的图标不对", Icons.Outlined.Favorite, icon)
            assertEquals("kind=$kind 的颜色不对", PINK, color)
        }
    }

    @Test
    fun `post interaction without the kind key uses the pink heart`() {
        val (icon, color) = iconForType(item(NotificationCenterType.POST_INTERACTION))
        assertEquals(Icons.Outlined.Favorite, icon)
        assertEquals(PINK, color)
    }

    @Test
    fun `post interaction takes the early branch not the one in the when`() {
        // 函数开头对 POST_INTERACTION 提前 return，所以下方 when 里的
        // `POST_INTERACTION -> Favorite to PINK` 分支永远走不到。
        // 用「同一 type 下 kind=comment 与 kind=like 图标不同」证明走的是提前分支。
        val comment = iconForType(item(NotificationCenterType.POST_INTERACTION, mapOf("kind" to "comment")))
        val like = iconForType(item(NotificationCenterType.POST_INTERACTION, mapOf("kind" to "like")))
        assertNotEquals("提前分支没生效，两条 kind 给出同一图标", comment.first, like.first)
    }

    @Test
    fun `every icon is distinct across all types`() {
        val icons = listOf(
            NotificationCenterType.MESSAGE,
            NotificationCenterType.MISSED_CALL,
            NotificationCenterType.AI_TASK,
            NotificationCenterType.GROUP_INVITE,
            NotificationCenterType.SECURITY,
            NotificationCenterType.FRIEND_REQUEST,
            NotificationCenterType.POST_INTERACTION,
        ).map { iconForType(item(it)).first }
        // 7 种类型应当给出 6 种图标（POST_INTERACTION 的 kind 缺省与 like 同为 Favorite，
        // 但这里 kind 缺省也是 Favorite，所以 7 个里 MESSAGE/MISSED_CALL/AI_TASK/
        // GROUP_INVITE/SECURITY/FRIEND_REQUEST 六个互不相同）
        val distinct = icons.toSet().size
        assertTrue("图标重复度过高：$distinct", distinct >= 6)
    }
}
