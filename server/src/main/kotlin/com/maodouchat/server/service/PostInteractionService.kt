package com.maodouchat.server.service

/** B10：动态/评论点赞交互子域（幂等 + 唯一约束冲突回读）。
 *
 * 实现已按域拆出：[PostLikeActions.kt]（动态点赞）、[CommentLikeActions.kt]（评论点赞）。
 */
class PostInteractionService
