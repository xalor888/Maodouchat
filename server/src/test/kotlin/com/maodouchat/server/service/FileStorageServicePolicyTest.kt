package com.maodouchat.server.service

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStorageServicePolicyTest {
    @Test
    fun `post image filename is scoped to uploader`() {
        assertTrue(FileStorageService.isOwnedPostImageFilename("post_user-1_0123abcd.jpg", "user-1"))
        assertFalse(FileStorageService.isOwnedPostImageFilename("post_user-2_0123abcd.jpg", "user-1"))
        assertFalse(FileStorageService.isOwnedPostImageFilename("post_user-1_0123abcd.png", "user-1"))
        assertFalse(FileStorageService.isOwnedPostImageFilename("post_user-1_..jpg", "user-1"))
    }

    @Test
    fun `group avatar filename is scoped to chat`() {
        assertTrue(FileStorageService.isOwnedGroupAvatarFilename("group_chat-1_0123abcd.jpg", "chat-1"))
        assertFalse(FileStorageService.isOwnedGroupAvatarFilename("group_chat-2_0123abcd.jpg", "chat-1"))
        assertFalse(FileStorageService.isOwnedGroupAvatarFilename("group_chat-1_0123abcd.png", "chat-1"))
    }

    @Test
    fun `group avatar url only accepts canonical owned route`() {
        val url = "${com.maodouchat.server.config.ServerConfig.baseUrl.trimEnd('/')}/api/chats/chat-1/avatar/file/group_chat-1_0123abcd.jpg"
        assertTrue(FileStorageService.groupAvatarFilename(url) == "group_chat-1_0123abcd.jpg")
        assertNull(FileStorageService.groupAvatarFilename("https://example.invalid/group_chat-1_0123abcd.jpg"))
        assertNull(FileStorageService.groupAvatarFilename(url.replace("/chat-1/", "/chat-2/")))
        assertNull(FileStorageService.groupAvatarFilename(url, expectedChatId = "chat-2"))
    }

    @Test
    fun `relative path resolution enforces path component boundary`() {
        val root = Paths.get("build", "storage-root").toAbsolutePath().normalize()
        assertTrue(FileStorageService.resolveRelativePath(root, "posts/image.jpg")?.startsWith(root) == true)
        assertNull(FileStorageService.resolveRelativePath(root, "../storage-root-evil/image.jpg"))
        assertNull(FileStorageService.resolveRelativePath(root, root.resolve("outside.jpg").toString()))
    }
}
