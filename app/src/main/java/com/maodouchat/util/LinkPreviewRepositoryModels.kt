package com.maodouchat.util

import kotlinx.coroutines.sync.Mutex

internal class InFlightLock(val mutex: Mutex = Mutex(), var users: Int = 0)
