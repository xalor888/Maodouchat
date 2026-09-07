package com.maodouchat.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.maodouchat.R

/**
 * P08：用户面链接的 Android 侧打开器。
 *
 * 决策在 [AppLinkRouter.resolveUserFacingUrl]；此处只做 Intent 派发。
 * - [AppLinkDestination.ExternalUrl] → 系统浏览器（不钉包名）
 * - 其它目标 → 钉本包 `ACTION_VIEW`，走 Manifest 深链 → MainActivity / 认证门闩
 */
object AppLinkOpener {

    fun openUserFacingUrl(context: Context, raw: String): Boolean {
        return when (val result = AppLinkRouter.resolveUserFacingUrl(raw)) {
            is AppLinkParseResult.Rejected -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_open_link_failed),
                    Toast.LENGTH_SHORT
                ).show()
                false
            }
            is AppLinkParseResult.Accepted -> openDestination(context, result.destination, raw)
        }
    }

    private fun openDestination(
        context: Context,
        destination: AppLinkDestination,
        originalRaw: String
    ): Boolean {
        return runCatching {
            val intent = when (destination) {
                is AppLinkDestination.ExternalUrl ->
                    Intent(Intent.ACTION_VIEW, Uri.parse(destination.url))
                else ->
                    Intent(Intent.ACTION_VIEW, Uri.parse(originalRaw.trim()))
                        .setPackage(context.packageName)
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            Toast.makeText(
                context,
                context.getString(R.string.chat_open_link_failed),
                Toast.LENGTH_SHORT
            ).show()
            false
        }
    }
}
