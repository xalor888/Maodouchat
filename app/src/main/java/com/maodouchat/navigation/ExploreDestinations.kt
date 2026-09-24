package com.maodouchat.navigation

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink

/**
 * 动态/联系人域目的地（P08：自 `NavGraph.kt` 迁出的第二个 feature 簇）。
 * 只依赖 `navController`；逐字搬运，行为不变。
 */
fun NavGraphBuilder.exploreDestinations(navController: NavHostController) {
    composable(Routes.MY_QR_CODE) {
        com.maodouchat.ui.screen.contacts.MyQrCodeScreen(
            onBack = { navController.popBackStack() },
            onOpenScan = { navController.navigate(Routes.SCAN) }
        )
    }
    composable(Routes.SCAN) {
        com.maodouchat.ui.screen.contacts.ScanScreen(
            onBack = { navController.popBackStack() },
            onAddContact = { user ->
                com.maodouchat.call.CallOrchestrator.requestDirectChat(user.id, user.name)
            },
            onOpenChat = { chatId ->
                navController.navigate(Routes.chatDetail(chatId)) {
                    launchSingleTop = true
                }
            },
            onJoinGroupInvite = { inviteCode ->
                navController.navigate(Routes.joinGroupInvite(inviteCode)) {
                    launchSingleTop = true
                }
            },
        )
    }
    composable(
        route = Routes.JOIN_GROUP_INVITE,
        arguments = listOf(navArgument("inviteCode") { type = NavType.StringType }),
    ) { entry ->
        val inviteCode = Uri.decode(entry.arguments?.getString("inviteCode") ?: "")
        com.maodouchat.ui.screen.contacts.JoinGroupInviteScreen(
            inviteCode = inviteCode,
            onBack = { navController.popBackStack() },
            onJoined = { chatId ->
                navController.navigate(Routes.chatDetail(chatId)) {
                    popUpTo(Routes.JOIN_GROUP_INVITE) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    composable(Routes.NEARBY) {
        androidx.compose.runtime.LaunchedEffect(Unit) { navController.popBackStack() }
    }
    composable(Routes.MOMENTS) {
        com.maodouchat.ui.screen.explore.MomentsScreen(
            onBack = { navController.popBackStack() },
            onOpenAuthor = { authorId -> navController.navigate(Routes.authorProfile(authorId)) },
            onOpenPost = { postId -> navController.navigate(Routes.postDetail(postId)) }
        )
    }
    composable(
        route = Routes.AUTHOR_PROFILE,
        arguments = listOf(navArgument("authorId") { type = NavType.StringType })
    ) { entry ->
        val authorId = Uri.decode(entry.arguments?.getString("authorId") ?: "")
        com.maodouchat.ui.screen.explore.AuthorProfileScreen(
            authorId = authorId,
            onBack = { navController.popBackStack() },
            onOpenChat = { id -> com.maodouchat.call.CallOrchestrator.requestDirectChat(id, "") },
            onOpenPost = { postId -> navController.navigate(Routes.postDetail(postId)) }
        )
    }
    composable(
        route = Routes.POST_DETAIL,
        arguments = listOf(
            navArgument("postId") { type = NavType.StringType },
            // 1.132：通知跳转定位到具体评论
            navArgument("comment") { type = NavType.StringType; defaultValue = "" }
        )
    ) { entry ->
        val postId = Uri.decode(entry.arguments?.getString("postId") ?: "")
        val commentId = entry.arguments?.getString("comment")?.takeIf { it.isNotBlank() }
        com.maodouchat.ui.screen.explore.PostDetailScreen(
            postId = postId,
            initialCommentId = commentId,
            onBack = { navController.popBackStack() },
            // 1.107：详情页作者行 → 作者主页
            onOpenAuthor = { authorId -> navController.navigate(Routes.authorProfile(authorId)) { launchSingleTop = true } }
        )
    }
    composable(
        route = Routes.PUBLIC_PROFILE,
        arguments = listOf(navArgument("username") { type = NavType.StringType }),
        // P08：深链模式唯一事实源见 AppLinkRouter.publicProfileDeepLinkPatterns。
        deepLinks = AppLinkRouter.publicProfileDeepLinkPatterns.map { pattern ->
            navDeepLink { uriPattern = pattern }
        }
    ) { entry ->
        val username = Uri.decode(entry.arguments?.getString("username") ?: "")
        if (username.isNotBlank()) {
            com.maodouchat.ui.screen.explore.PublicProfileScreen(
                username = username,
                onBack = { navController.popBackStack() },
                onStartChat = { userId ->
                    com.maodouchat.call.CallOrchestrator.requestDirectChat(userId, "")
                    navController.popBackStack()
                }
            )
        }
    }
}
