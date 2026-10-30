package com.example.social.application

import com.example.social.domain.*
import java.time.Duration
import java.time.Instant

data class PostCursor(val createdAt: Instant, val id: PostId)

data class PostSummary(
    val id: PostId,
    val authorId: UserId,
    val body: String,
    val createdAt: Instant,
    val hashtags: Set<Hashtag>,
    val likeCount: Long,
    val commentCount: Long,
    val likedByViewer: Boolean,
    val visibility: PostVisibility = PostVisibility.PUBLIC,
    val font: PostFont = PostFont.SANS,
    val size: PostSize = PostSize.NORMAL,
    val imageCount: Int = 0,
    val mediaTypes: List<String> = emptyList()
)

data class FeedPage(val posts: List<PostSummary>, val next: PostCursor?)
data class ForYouCursor(val followed: Boolean, val createdAt: Instant, val id: PostId)
data class ForYouPage(val posts: List<PostSummary>, val next: ForYouCursor?)
data class HashtagCount(val name: String, val posts: Long)

enum class TrendWindow(val length: Duration) {
    DAY(Duration.ofHours(24)), WEEK(Duration.ofDays(7));

    companion object {
        fun parse(value: String): TrendWindow = when (value) {
            "24h" -> DAY
            "7d" -> WEEK
            else -> throw IllegalArgumentException("Invalid trend period")
        }
    }
}

data class TrendingHashtag(val name: String, val posts: Long, val previousPosts: Long) {
    val growthPercent: Long? get() = if (previousPosts == 0L) null else (posts - previousPosts) * 100 / previousPosts
}
data class CommentCursor(val createdAt: Instant, val id: CommentId)
data class CommentPage(val comments: List<Comment>, val next: CommentCursor?)

interface PostQueries {
    fun visibleComments(post: PostId, limit: Int, after: CommentCursor?, viewer: UserId?): CommentPage = comments(post, limit, after)
    fun followingFeed(viewer: UserId, limit: Int, before: PostCursor? = null, tag: Hashtag? = null, author: UserId? = null): FeedPage =
        throw UnsupportedOperationException("Following feed is not configured")
    fun post(id: PostId, viewer: UserId? = null): PostSummary? = null
    fun followedHashtagFeed(viewer: UserId, limit: Int, before: PostCursor? = null): FeedPage =
        throw UnsupportedOperationException("Followed hashtag feed is not configured")
    fun feed(limit: Int, before: PostCursor? = null, viewer: UserId? = null, tag: Hashtag? = null, author: UserId? = null): FeedPage
    fun comments(post: PostId, limit: Int, after: CommentCursor? = null): CommentPage
    fun trendingHashtags(limit: Int): List<HashtagCount> = emptyList()
    fun trending(limit: Int, window: TrendWindow, now: Instant): List<TrendingHashtag> = emptyList()
    fun searchHashtags(query: String, limit: Int, offset: Int, viewer: UserId?): List<HashtagCount> = emptyList()
    fun hashtagPostCount(tag: Hashtag, viewer: UserId?): Long = 0
    fun forYou(viewer: UserId, limit: Int, before: ForYouCursor? = null, tag: Hashtag): ForYouPage =
        throw UnsupportedOperationException("For you feed is not configured")
}
