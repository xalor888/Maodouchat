package com.maodouchat.server.repository

import org.jetbrains.exposed.sql.ResultRow

data class Allowed(val chat: ResultRow) : GroupAdminAccess
data class Denied(val result: GroupMemberMutationResult) : GroupAdminAccess
