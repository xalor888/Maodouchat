package com.maodouchat.data.repository

/** 两个集合分开消费时的载体（星标页要分别判断「密聊域」与「锁定时」）。 */
data class RedactionSets(val locked: Set<String>, val secret: Set<String>)
