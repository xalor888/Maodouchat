package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentToolNamePolicyTest {

    @Test
    fun `messaging tool names`() {
        assertEquals("list_chats", AgentToolNamePolicy.LIST_CHATS)
        assertEquals("get_chat_history", AgentToolNamePolicy.GET_CHAT_HISTORY)
        assertEquals("search_messages", AgentToolNamePolicy.SEARCH_MESSAGES)
        assertEquals("get_chat", AgentToolNamePolicy.GET_CHAT)
        assertEquals("list_starred_messages", AgentToolNamePolicy.LIST_STARRED_MESSAGES)
        assertEquals("list_pinned_messages", AgentToolNamePolicy.LIST_PINNED_MESSAGES)
        assertEquals("send_text_message", AgentToolNamePolicy.SEND_TEXT_MESSAGE)
        assertEquals("update_chat", AgentToolNamePolicy.UPDATE_CHAT)
        assertEquals("set_draft", AgentToolNamePolicy.SET_DRAFT)
        assertEquals("star_message", AgentToolNamePolicy.STAR_MESSAGE)
        assertEquals("delete_local_message", AgentToolNamePolicy.DELETE_LOCAL_MESSAGE)
        assertEquals("set_contact_nickname", AgentToolNamePolicy.SET_CONTACT_NICKNAME)
        assertEquals("mark_notification_read", AgentToolNamePolicy.MARK_NOTIFICATION_READ)
        assertEquals("revoke_message", AgentToolNamePolicy.REVOKE_MESSAGE)
        assertEquals("react_to_message", AgentToolNamePolicy.REACT_TO_MESSAGE)
        assertEquals("pin_message", AgentToolNamePolicy.PIN_MESSAGE)
        assertEquals("create_direct_chat", AgentToolNamePolicy.CREATE_DIRECT_CHAT)
        assertEquals("create_group", AgentToolNamePolicy.CREATE_GROUP)
        assertEquals("rename_group", AgentToolNamePolicy.RENAME_GROUP)
        assertEquals("update_group_announcement", AgentToolNamePolicy.UPDATE_GROUP_ANNOUNCEMENT)
        assertEquals("add_group_members", AgentToolNamePolicy.ADD_GROUP_MEMBERS)
        assertEquals("remove_group_member", AgentToolNamePolicy.REMOVE_GROUP_MEMBER)
        assertEquals("mute_group_member", AgentToolNamePolicy.MUTE_GROUP_MEMBER)
        assertEquals("delete_chat", AgentToolNamePolicy.DELETE_CHAT)
    }

    @Test
    fun `social tool names`() {
        assertEquals("get_contacts", AgentToolNamePolicy.GET_CONTACTS)
        assertEquals("get_me", AgentToolNamePolicy.GET_ME)
        assertEquals("list_friend_requests", AgentToolNamePolicy.LIST_FRIEND_REQUESTS)
        assertEquals("list_friends", AgentToolNamePolicy.LIST_FRIENDS)
        assertEquals("search_users", AgentToolNamePolicy.SEARCH_USERS)
        assertEquals("list_posts", AgentToolNamePolicy.LIST_POSTS)
        assertEquals("get_post", AgentToolNamePolicy.GET_POST)
        assertEquals("list_post_comments", AgentToolNamePolicy.LIST_POST_COMMENTS)
        assertEquals("list_blocked_users", AgentToolNamePolicy.LIST_BLOCKED_USERS)
        assertEquals("send_friend_request", AgentToolNamePolicy.SEND_FRIEND_REQUEST)
        assertEquals("accept_friend_request", AgentToolNamePolicy.ACCEPT_FRIEND_REQUEST)
        assertEquals("reject_friend_request", AgentToolNamePolicy.REJECT_FRIEND_REQUEST)
        assertEquals("cancel_friend_request", AgentToolNamePolicy.CANCEL_FRIEND_REQUEST)
        assertEquals("remove_friend", AgentToolNamePolicy.REMOVE_FRIEND)
        assertEquals("block_user", AgentToolNamePolicy.BLOCK_USER)
        assertEquals("unblock_user", AgentToolNamePolicy.UNBLOCK_USER)
        assertEquals("create_text_post", AgentToolNamePolicy.CREATE_TEXT_POST)
        assertEquals("like_post", AgentToolNamePolicy.LIKE_POST)
        assertEquals("comment_on_post", AgentToolNamePolicy.COMMENT_ON_POST)
        assertEquals("delete_post", AgentToolNamePolicy.DELETE_POST)
    }

    @Test
    fun `task tool names`() {
        assertEquals("list_drafts", AgentToolNamePolicy.LIST_DRAFTS)
        assertEquals("get_draft", AgentToolNamePolicy.GET_DRAFT)
        assertEquals("list_local_tasks", AgentToolNamePolicy.LIST_LOCAL_TASKS)
        assertEquals("list_missed_calls", AgentToolNamePolicy.LIST_MISSED_CALLS)
        assertEquals("list_notifications", AgentToolNamePolicy.LIST_NOTIFICATIONS)
        assertEquals("create_local_task", AgentToolNamePolicy.CREATE_LOCAL_TASK)
        assertEquals("complete_local_task", AgentToolNamePolicy.COMPLETE_LOCAL_TASK)
        assertEquals("delete_local_task", AgentToolNamePolicy.DELETE_LOCAL_TASK)
    }

    @Test
    fun `all tool names are distinct`() {
        val all = listOf(
            AgentToolNamePolicy.LIST_CHATS,
            AgentToolNamePolicy.GET_CHAT_HISTORY,
            AgentToolNamePolicy.SEARCH_MESSAGES,
            AgentToolNamePolicy.GET_CHAT,
            AgentToolNamePolicy.LIST_STARRED_MESSAGES,
            AgentToolNamePolicy.LIST_PINNED_MESSAGES,
            AgentToolNamePolicy.SEND_TEXT_MESSAGE,
            AgentToolNamePolicy.UPDATE_CHAT,
            AgentToolNamePolicy.SET_DRAFT,
            AgentToolNamePolicy.STAR_MESSAGE,
            AgentToolNamePolicy.DELETE_LOCAL_MESSAGE,
            AgentToolNamePolicy.SET_CONTACT_NICKNAME,
            AgentToolNamePolicy.MARK_NOTIFICATION_READ,
            AgentToolNamePolicy.REVOKE_MESSAGE,
            AgentToolNamePolicy.REACT_TO_MESSAGE,
            AgentToolNamePolicy.PIN_MESSAGE,
            AgentToolNamePolicy.CREATE_DIRECT_CHAT,
            AgentToolNamePolicy.CREATE_GROUP,
            AgentToolNamePolicy.RENAME_GROUP,
            AgentToolNamePolicy.UPDATE_GROUP_ANNOUNCEMENT,
            AgentToolNamePolicy.ADD_GROUP_MEMBERS,
            AgentToolNamePolicy.REMOVE_GROUP_MEMBER,
            AgentToolNamePolicy.MUTE_GROUP_MEMBER,
            AgentToolNamePolicy.DELETE_CHAT,
            AgentToolNamePolicy.GET_CONTACTS,
            AgentToolNamePolicy.GET_ME,
            AgentToolNamePolicy.LIST_FRIEND_REQUESTS,
            AgentToolNamePolicy.LIST_FRIENDS,
            AgentToolNamePolicy.SEARCH_USERS,
            AgentToolNamePolicy.LIST_POSTS,
            AgentToolNamePolicy.GET_POST,
            AgentToolNamePolicy.LIST_POST_COMMENTS,
            AgentToolNamePolicy.LIST_BLOCKED_USERS,
            AgentToolNamePolicy.SEND_FRIEND_REQUEST,
            AgentToolNamePolicy.ACCEPT_FRIEND_REQUEST,
            AgentToolNamePolicy.REJECT_FRIEND_REQUEST,
            AgentToolNamePolicy.CANCEL_FRIEND_REQUEST,
            AgentToolNamePolicy.REMOVE_FRIEND,
            AgentToolNamePolicy.BLOCK_USER,
            AgentToolNamePolicy.UNBLOCK_USER,
            AgentToolNamePolicy.CREATE_TEXT_POST,
            AgentToolNamePolicy.LIKE_POST,
            AgentToolNamePolicy.COMMENT_ON_POST,
            AgentToolNamePolicy.DELETE_POST,
            AgentToolNamePolicy.LIST_DRAFTS,
            AgentToolNamePolicy.GET_DRAFT,
            AgentToolNamePolicy.LIST_LOCAL_TASKS,
            AgentToolNamePolicy.LIST_MISSED_CALLS,
            AgentToolNamePolicy.LIST_NOTIFICATIONS,
            AgentToolNamePolicy.CREATE_LOCAL_TASK,
            AgentToolNamePolicy.COMPLETE_LOCAL_TASK,
            AgentToolNamePolicy.DELETE_LOCAL_TASK
        )
        assertEquals(52, all.toSet().size)
    }
}
