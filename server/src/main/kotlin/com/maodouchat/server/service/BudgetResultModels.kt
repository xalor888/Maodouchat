package com.maodouchat.server.service

data object Allowed : BudgetResult
data class Exceeded(
    val usedTokens: Long,
    val budgetTokens: Long,
    val retryAfterSeconds: Long
) : BudgetResult
