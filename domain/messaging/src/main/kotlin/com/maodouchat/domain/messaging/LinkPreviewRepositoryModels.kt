package com.maodouchat.domain.messaging

import kotlinx.coroutines.sync.Mutex

class InFlightLock(val mutex: Mutex = Mutex(), var users: Int = 0)
