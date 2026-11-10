package com.maodouchat.ui.screen.contacts

import com.maodouchat.R
import com.maodouchat.domain.contacts.ContactsMessage

/** domain 侧 ContactsMessage 到 app 字符串资源的映射。 */
internal fun contactsMessageResId(message: ContactsMessage): Int = when (message) {
    ContactsMessage.SESSION_EXPIRED -> R.string.error_session_expired
    ContactsMessage.OPERATION_FAILED -> R.string.error_operation_failed
    ContactsMessage.CONTACTS_LOAD_FAILED -> R.string.contacts_load_failed
    ContactsMessage.CONTACTS_SEARCH_FAILED -> R.string.contacts_search_failed
    ContactsMessage.FRIEND_REQUESTS_DISABLED -> R.string.friend_requests_disabled
    ContactsMessage.SECRET_CHAT_FEATURE_DISABLED -> R.string.secret_chat_feature_disabled
    ContactsMessage.CONTACTS_ENTER_GROUP_NAME -> R.string.contacts_enter_group_name
    ContactsMessage.CHAT_CHANNEL_NAME_HINT -> R.string.chat_channel_name_hint
    ContactsMessage.CONTACTS_CREATE_CHAT_FAILED -> R.string.contacts_create_chat_failed
    ContactsMessage.CONTACTS_FRIEND_ACCEPTED -> R.string.contacts_friend_accepted
    ContactsMessage.CONTACTS_FRIEND_REJECTED -> R.string.contacts_friend_rejected
    ContactsMessage.CONTACTS_FRIEND_REMOVED -> R.string.contacts_friend_removed
    ContactsMessage.CONTACTS_FRIEND_BLOCKED -> R.string.contacts_friend_blocked
    ContactsMessage.CONTACTS_FRIEND_REQUEST_SENT -> R.string.contacts_friend_request_sent
    ContactsMessage.CONTACTS_FRIEND_REQUEST_FAILED -> R.string.contacts_friend_request_failed
    ContactsMessage.CONTACTS_FRIEND_ACCEPTED_ALL -> R.string.contacts_friend_accepted_all
    ContactsMessage.CONTACTS_FRIEND_REJECTED_ALL -> R.string.contacts_friend_rejected_all
    ContactsMessage.CONTACTS_FRIEND_BATCH_PARTIAL -> R.string.contacts_friend_batch_partial
    ContactsMessage.CONTACTS_NICKNAME_SAVED -> R.string.contacts_nickname_saved
    ContactsMessage.CONTACTS_GROUP_UNNAMED -> R.string.contacts_group_unnamed
    ContactsMessage.CONTACTS_GROUP_INVITE_ACCEPTED -> R.string.contacts_group_invite_accepted
    ContactsMessage.CONTACTS_GROUP_INVITE_DECLINED -> R.string.contacts_group_invite_declined
}
