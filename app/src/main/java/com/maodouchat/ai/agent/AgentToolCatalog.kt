package com.maodouchat.ai.agent

import com.maodouchat.core.model.ToolSpec
import com.maodouchat.core.model.acceptFriendRequestToolSpec
import com.maodouchat.core.model.addGroupMembersToolSpec
import com.maodouchat.core.model.blockUserToolSpec
import com.maodouchat.core.model.cancelFriendRequestToolSpec
import com.maodouchat.core.model.commentOnPostToolSpec
import com.maodouchat.core.model.completeLocalTaskToolSpec
import com.maodouchat.core.model.createDirectChatToolSpec
import com.maodouchat.core.model.createGroupToolSpec
import com.maodouchat.core.model.createLocalTaskToolSpec
import com.maodouchat.core.model.createTextPostToolSpec
import com.maodouchat.core.model.deleteChatToolSpec
import com.maodouchat.core.model.deleteLocalMessageToolSpec
import com.maodouchat.core.model.deleteLocalTaskToolSpec
import com.maodouchat.core.model.deletePostToolSpec
import com.maodouchat.core.model.getChatHistoryToolSpec
import com.maodouchat.core.model.getChatToolSpec
import com.maodouchat.core.model.getContactsToolSpec
import com.maodouchat.core.model.getDraftToolSpec
import com.maodouchat.core.model.getMeToolSpec
import com.maodouchat.core.model.getPostToolSpec
import com.maodouchat.core.model.likePostToolSpec
import com.maodouchat.core.model.listBlockedUsersToolSpec
import com.maodouchat.core.model.listChatsToolSpec
import com.maodouchat.core.model.listDraftsToolSpec
import com.maodouchat.core.model.listFriendRequestsToolSpec
import com.maodouchat.core.model.listFriendsToolSpec
import com.maodouchat.core.model.listLocalTasksToolSpec
import com.maodouchat.core.model.listMissedCallsToolSpec
import com.maodouchat.core.model.listNotificationsToolSpec
import com.maodouchat.core.model.listPinnedMessagesToolSpec
import com.maodouchat.core.model.listPostCommentsToolSpec
import com.maodouchat.core.model.listPostsToolSpec
import com.maodouchat.core.model.listStarredMessagesToolSpec
import com.maodouchat.core.model.markNotificationReadToolSpec
import com.maodouchat.core.model.muteGroupMemberToolSpec
import com.maodouchat.core.model.pinMessageToolSpec
import com.maodouchat.core.model.reactToMessageToolSpec
import com.maodouchat.core.model.rejectFriendRequestToolSpec
import com.maodouchat.core.model.removeFriendToolSpec
import com.maodouchat.core.model.removeGroupMemberToolSpec
import com.maodouchat.core.model.renameGroupToolSpec
import com.maodouchat.core.model.revokeMessageToolSpec
import com.maodouchat.core.model.rewriteTextToolSpec
import com.maodouchat.core.model.searchMessagesToolSpec
import com.maodouchat.core.model.searchUsersToolSpec
import com.maodouchat.core.model.sendFriendRequestToolSpec
import com.maodouchat.core.model.sendTextMessageToolSpec
import com.maodouchat.core.model.setContactNicknameToolSpec
import com.maodouchat.core.model.setDraftToolSpec
import com.maodouchat.core.model.starMessageToolSpec
import com.maodouchat.core.model.unblockUserToolSpec
import com.maodouchat.core.model.updateChatToolSpec
import com.maodouchat.core.model.updateGroupAnnouncementToolSpec

internal val agentToolCatalog: List<ToolSpec> = listOf(
    listChatsToolSpec,
    getChatHistoryToolSpec,
    searchMessagesToolSpec,
    getContactsToolSpec,
    getMeToolSpec,
    getChatToolSpec,
    listStarredMessagesToolSpec,
    listDraftsToolSpec,
    getDraftToolSpec,
    listLocalTasksToolSpec,
    listMissedCallsToolSpec,
    listNotificationsToolSpec,
    rewriteTextToolSpec,
    createLocalTaskToolSpec,
    sendTextMessageToolSpec,
    updateChatToolSpec,
    setDraftToolSpec,
    starMessageToolSpec,
    completeLocalTaskToolSpec,
    deleteLocalTaskToolSpec,
    setContactNicknameToolSpec,
    deleteLocalMessageToolSpec,
    markNotificationReadToolSpec,
    listPinnedMessagesToolSpec,
    listFriendRequestsToolSpec,
    listFriendsToolSpec,
    searchUsersToolSpec,
    listPostsToolSpec,
    getPostToolSpec,
    listPostCommentsToolSpec,
    listBlockedUsersToolSpec,
    revokeMessageToolSpec,
    reactToMessageToolSpec,
    pinMessageToolSpec,
    sendFriendRequestToolSpec,
    acceptFriendRequestToolSpec,
    rejectFriendRequestToolSpec,
    cancelFriendRequestToolSpec,
    removeFriendToolSpec,
    blockUserToolSpec,
    unblockUserToolSpec,
    createTextPostToolSpec,
    likePostToolSpec,
    commentOnPostToolSpec,
    deletePostToolSpec,
    createDirectChatToolSpec,
    createGroupToolSpec,
    renameGroupToolSpec,
    updateGroupAnnouncementToolSpec,
    addGroupMembersToolSpec,
    removeGroupMemberToolSpec,
    muteGroupMemberToolSpec,
    deleteChatToolSpec,
)
