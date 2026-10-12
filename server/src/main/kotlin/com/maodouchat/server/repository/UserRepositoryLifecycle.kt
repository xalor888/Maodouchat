package com.maodouchat.server.repository

// 账号生命周期委托簇：原 UserRepository 成员按域拆出的顶层扩展函数（同包调用，零行为改动）。

fun UserRepository.deleteAccount(userId: String, password: String): AccountDeactivationResult? = accountLifecycleService.deleteAccount(userId, password)

fun UserRepository.adminDeactivateAccount(userId: String, actorId: String): AccountDeactivationResult? = accountLifecycleService.adminDeactivateAccount(userId, actorId)
