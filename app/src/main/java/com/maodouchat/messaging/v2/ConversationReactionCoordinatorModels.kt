package com.maodouchat.messaging.v2

import kotlinx.coroutines.sync.Mutex

internal class ReactionLock(
    val mutex: Mutex = Mutex(),
    var users: Int = 0,
)
