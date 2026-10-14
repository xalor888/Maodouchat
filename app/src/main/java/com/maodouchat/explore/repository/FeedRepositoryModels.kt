package com.maodouchat.explore.repository

data class FeedSession(val ownerUserId: String)

class FeedSessionChangedException : IllegalStateException("Feed session changed")
