package com.maodouchat.server.repository


/** 群接龙 / 群 PK 委托簇：薄转发至 GroupChainRepository / GroupPkRepository。 */

private val chainRepository = GroupChainRepository()

private val pkRepository = GroupPkRepository()

// ── 群接龙（委托 GroupChainRepository）────────────────

fun GroupCheckinRepository.createChain(chatId: String, creatorId: String, title: String, topic: String, maxEntries: Int): GroupChainRepository.ChainDto? = chainRepository.createChain(chatId, creatorId, title, topic, maxEntries)

fun GroupCheckinRepository.listChains(chatId: String, viewerId: String, limit: Int = 30): List<GroupChainRepository.ChainDto> = chainRepository.listChains(chatId, viewerId, limit)

fun GroupCheckinRepository.getChain(chainId: String, viewerId: String): GroupChainRepository.ChainDto? = chainRepository.getChain(chainId, viewerId)

fun GroupCheckinRepository.joinChain(chainId: String, userId: String, content: String): GroupChainRepository.ChainDto? = chainRepository.joinChain(chainId, userId, content)

// ── 群 PK（委托 GroupPkRepository）────────────────────

fun GroupCheckinRepository.createPk(chatId: String, creatorId: String, leftTitle: String, rightTitle: String): GroupPkRepository.PkDto? = pkRepository.createPk(chatId, creatorId, leftTitle, rightTitle)

fun GroupCheckinRepository.listChatPks(chatId: String, viewerId: String, limit: Int = 30): List<GroupPkRepository.PkDto> = pkRepository.listChatPks(chatId, viewerId, limit)

fun GroupCheckinRepository.getPk(pkId: String, viewerId: String): GroupPkRepository.PkDto? = pkRepository.getPk(pkId, viewerId)

fun GroupCheckinRepository.votePk(pkId: String, userId: String, choice: String): GroupPkRepository.PkDto? = pkRepository.votePk(pkId, userId, choice)

fun GroupCheckinRepository.closePk(pkId: String, userId: String): GroupPkRepository.PkDto? = pkRepository.closePk(pkId, userId)
