package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table

// 动态表。

object Posts : Table("posts") {
    val id = varchar("id", 100)
    val authorId = varchar("author_id", 50) references Users.id
    val content = text("content")
    val imageUrls = text("image_urls").default("[]")
    val visibility = varchar("visibility", 20).default("PUBLIC")
    val status = varchar("status", 20).default("PUBLISHED")
    val createdAt = long("created_at")
    /** 编辑时间戳，null 表示未编辑 */
    val editedAt = long("edited_at").nullable()
    override val primaryKey = PrimaryKey(id)

    // getFeed 按 createdAt DESC 分页游标翻页 → 索引截断全表排序
    init {
        index("idx_posts_created_at", false, createdAt)
    }
}

/** 动态图片唯一占用：一张上传图片只能被一条动态使用（DB 级防重复，跨进程/重启仍生效）。 */

object PostImageClaims : Table("post_image_claims") {
    val filename = varchar("filename", 200)
    val postId = varchar("post_id", 100) references Posts.id
    val claimedAt = long("claimed_at")
    override val primaryKey = PrimaryKey(filename)

    init {
        index("idx_post_image_claims_post", false, postId)
    }
}

object PostLikes : Table("post_likes") {
    val postId = varchar("post_id", 100) references Posts.id
    val userId = varchar("user_id", 50) references Users.id
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(postId, userId)

    // toPostResponse 按 postId 聚合计数 → 索引让 count() 直接走覆盖索引
    init {
        index("idx_post_likes_post_id", false, postId)
    }
}

object PostComments : Table("post_comments") {
    val id = varchar("id", 100)
    val postId = varchar("post_id", 100) references Posts.id
    val authorId = varchar("author_id", 50) references Users.id
    val content = text("content")
    val createdAt = long("created_at")
    /** 1.76：评论回复——被回复评论 id（null=顶级评论）。 */
    val parentId = varchar("parent_id", 100).nullable().default(null)
    override val primaryKey = PrimaryKey(id)

    // getComments 按 postId + createdAt 排序分页 → 复合索引避免 filesort
    init {
        index("idx_post_comments_post_created", false, postId, createdAt)
    }
}

/** 评论点赞（1.52）：一行一赞，PK(commentId, userId) 防重复，可取消。 */

object CommentLikes : Table("comment_likes") {
    val commentId = varchar("comment_id", 100) references PostComments.id
    val userId = varchar("user_id", 50) references Users.id
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(commentId, userId)

    init {
        index("idx_comment_likes_comment", false, commentId)
    }
}

/** 好友申请：PENDING / ACCEPTED / REJECTED / CANCELLED */

