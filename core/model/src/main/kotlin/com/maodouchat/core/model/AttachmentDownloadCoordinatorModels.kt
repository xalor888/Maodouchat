package com.maodouchat.core.model

import kotlinx.coroutines.sync.Mutex

class LockRef(val mutex: Mutex = Mutex(), var users: Int = 0)
