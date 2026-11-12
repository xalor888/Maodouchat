package com.maodouchat.ui.screen.chatlist

import android.app.Application
import com.maodouchat.data.repository.ChatFolderNetworkRepository
import com.maodouchat.domain.conversation.ChatFolderController
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.CurrentSession
import com.maodouchat.util.ChatFolderPreferences
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.util.UnreadPriorityPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** ChatListViewModel 的文件夹控制器接线：本地存取、开关、会话门禁与远端同步由 app 侧提供。 */
internal fun createChatFolderController(
    application: Application,
    scope: CoroutineScope,
    uiState: MutableStateFlow<ChatListUiState>,
): ChatFolderController = ChatFolderController(
    scope = scope,
    getFolders = { uiState.value.folders },
    updateFolders = { folders -> uiState.update { it.copy(folders = folders) } },
    getSelectedFolderId = { uiState.value.selectedFolderId },
    updateSelectedFolderId = { id -> uiState.update { it.copy(selectedFolderId = id) } },
    getSecretChatIds = { uiState.value.secretChatIds },
    getChats = { uiState.value.chats },
    updateUnreadPriorityEnabled = { enabled -> uiState.update { it.copy(unreadPriorityEnabled = enabled) } },
    getLocalFolders = { ChatFolderPreferences.getFolders(application) },
    setLocalFolders = { folders -> ChatFolderPreferences.setFolders(application, folders) },
    isUnreadPriorityEnabled = { UnreadPriorityPreferences.isEnabled(application) },
    persistUnreadPriorityEnabled = { enabled -> UnreadPriorityPreferences.setEnabled(application, enabled) },
    foldersEnabled = { RuntimeFlags.isEnabled(application, RuntimeFlags.CHAT_FOLDERS) },
    ownerUserId = { CurrentSession.ownerUserId() },
    hasSession = { CurrentSession.hasSession() },
    sessionMayContinue = { owner -> BackgroundSessionGate.mayContinue(expectedUserId = owner) },
    fetchRemoteFolders = { ChatFolderNetworkRepository().folders() },
    pushRemoteFolders = { folders -> ChatFolderNetworkRepository().putFolders(folders = folders) },
)
