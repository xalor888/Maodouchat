package com.maodouchat.core.model

data class FeedSession(val ownerUserId: String)

class FeedSessionChangedException : IllegalStateException("Feed session changed")
