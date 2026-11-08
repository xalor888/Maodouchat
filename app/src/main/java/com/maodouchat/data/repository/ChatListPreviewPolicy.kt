package com.maodouchat.data.repository

// 聊天列表预览策略已迁入 :core:model；留 typealias 过渡供 #1622 的 MessagePresentation.kt
//（其余调用方已改走 core），#1622 合入后拆除本文件并更新该调用方。
typealias ChatListPreviewPolicy = com.maodouchat.core.model.ChatListPreviewPolicy
