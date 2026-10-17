package com.maodouchat.server.repository

data class SessionCompromised(val userId: String, val sessionId: String) : RotateRefreshResult()
data object InvalidToken : RotateRefreshResult()
data object UserSuspended : RotateRefreshResult()
data object UserMissing : RotateRefreshResult()
