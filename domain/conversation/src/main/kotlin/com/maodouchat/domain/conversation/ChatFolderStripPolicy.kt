package com.maodouchat.domain.conversation


// 文件夹条芯片：密聊/锁定芯片有计数就显示，选中该系统文件夹时即使计数为 0 也显示。
object ChatFolderStripPolicy {

    fun showSecretChip(secretChatCount: Int, selectedFolderId: String?): Boolean =
        secretChatCount > 0 || selectedFolderId == ChatFolderPolicy.SYSTEM_SECRET_ID

    fun showLockedChip(lockedChatCount: Int, selectedFolderId: String?): Boolean =
        lockedChatCount > 0 || selectedFolderId == ChatFolderPolicy.SYSTEM_LOCKED_ID
}
