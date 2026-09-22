package com.maodouchat.ui.screen.chatdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G181b：Robolectric 冒烟——验证「Compose UI 能在 JVM 上跑」这个能力是否成立。
 *
 * 背景：`app/build.gradle.kts` 里原本写着「Robolectric 需要从互联网下载 Android SDK 镜像；
 * 在受限网络环境下无法运行」，于是这个项目的 Compose 覆盖只剩 instrumented 一条路
 * （G173b–G178b 的 18 条 UI 用例全在模拟器上跑）。本轮实测 Maven 上 Robolectric 的
 * 依赖与 android-all 镜像都是 HTTP 200，所以重新启用并验证。
 *
 * 这一条只做冒烟：能渲染 `SecretChatConfirmDialog` 并断言标题在，就说明能力成立。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RobolectricSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun secretChatConfirmRendersUnderRobolectric() {
        var confirm = 0
        compose.setContent {
            SecretChatConfirmDialog(visible = true, onConfirm = { confirm++ }, onDismiss = {})
        }
        // 文案从资源取（Robolectric 提供真实资源）。
        // ComposeTestRule 没有 .activity，用 InstrumentationRegistry 的 targetContext。
        val title = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
            .getString(com.maodouchat.R.string.secret_chat_confirm_enable_title)
        compose.onNodeWithText(title).assertIsDisplayed()
    }
}
