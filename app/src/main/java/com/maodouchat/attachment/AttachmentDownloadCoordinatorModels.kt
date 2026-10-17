package com.maodouchat.attachment

import kotlinx.coroutines.sync.Mutex

internal class LockRef(val mutex: Mutex = Mutex(), var users: Int = 0)
