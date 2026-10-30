package com.example.social.domain

import java.time.Instant
import java.util.UUID

@JvmInline
value class UserId(val value: UUID) {
    companion object {
        fun parse(raw: String) = UserId(UUID.fromString(raw))
        fun new() = UserId(UUID.randomUUID())
    }
}

@JvmInline
value class PostId(val value: UUID) {
    companion object {
        fun parse(raw: String) = PostId(UUID.fromString(raw))
        fun new() = PostId(UUID.randomUUID())
    }
}

@JvmInline
value class CommentId(val value: UUID) {
    companion object {
        fun new() = CommentId(UUID.randomUUID())
    }
}

@JvmInline
value class Hashtag(val value: String) {
    init {
        require(pattern.matches(value) && value == value.lowercase()) { "Invalid hashtag: $value" }
    }

    companion object {
        val pattern = Regex("[\\p{L}\\p{M}\\p{N}_]{1,50}")
        fun from(raw: String): Hashtag {
            val normalized = raw.removePrefix("#").lowercase()
            require(pattern.matches(normalized)) { "Invalid hashtag: $raw" }
            return Hashtag(normalized)
        }
    }
}

data class Comment(
    val id: CommentId = CommentId.new(),
    val authorId: UserId,
    val body: String,
    val createdAt: Instant = Instant.now(),
    val parentId: CommentId? = null,
    val attachmentType: String? = null
) {
    init {
        require(body.isNotBlank() || attachmentType != null) { "Comment must not be empty" }
        require(body.length <= 1_000) { "Comment must be at most 1000 characters" }
    }
}

enum class PostVisibility { PUBLIC, MEMBERS, PRIVATE;
    companion object {
        fun parse(value: String): PostVisibility = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("Invalid post audience")
    }
}

enum class PostFont { SANS, SERIF, MONOSPACE;
    companion object {
        fun parse(value: String): PostFont = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("Invalid post font")
    }
}

enum class PostSize { SMALL, NORMAL, LARGE;
    companion object {
        fun parse(value: String): PostSize = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("Invalid post size")
    }
}

class PostImage private constructor(private val content: ByteArray, val mediaType: String) {
    constructor(mediaType: String, bytes: ByteArray) : this(bytes.copyOf(), mediaType)
    val bytes: ByteArray get() = content.copyOf()
    val byteCount: Int get() = content.size
    fun readOnlyBytes(): java.nio.ByteBuffer = java.nio.ByteBuffer.wrap(content).asReadOnlyBuffer()
    fun withMediaType(mediaType: String): PostImage = PostImage(content, mediaType)
}

class Post(
    val id: PostId,
    val authorId: UserId,
    val body: String,
    hashtags: Set<Hashtag>,
    val createdAt: Instant,
    likes: Set<UserId> = emptySet(),
    comments: List<Comment> = emptyList(),
    val visibility: PostVisibility = PostVisibility.PUBLIC,
    val font: PostFont = PostFont.SANS,
    val size: PostSize = PostSize.NORMAL,
    val imageCount: Int = 0,
    mediaTypes: List<String> = emptyList()
) {
    /** Original attachment types in display order. imageCount also counts video and audio for compatibility. */
    val mediaTypes: List<String> = java.util.Collections.unmodifiableList(ArrayList(mediaTypes))
    val hashtags: Set<Hashtag> = java.util.Collections.unmodifiableSet(LinkedHashSet(hashtags))
    val likes: Set<UserId> = java.util.Collections.unmodifiableSet(LinkedHashSet(likes))
    val comments: List<Comment> = java.util.Collections.unmodifiableList(ArrayList(comments))

    fun copy(
        id: PostId = this.id, authorId: UserId = this.authorId, body: String = this.body,
        hashtags: Set<Hashtag> = this.hashtags, createdAt: Instant = this.createdAt,
        likes: Set<UserId> = this.likes, comments: List<Comment> = this.comments, visibility: PostVisibility = this.visibility,
        font: PostFont = this.font, size: PostSize = this.size, imageCount: Int = this.imageCount,
        mediaTypes: List<String> = this.mediaTypes
    ) = Post(id, authorId, body, hashtags, createdAt, likes, comments, visibility, font, size, imageCount, mediaTypes)

    override fun equals(other: Any?): Boolean = other is Post && id == other.id && authorId == other.authorId &&
        body == other.body && hashtags == other.hashtags && createdAt == other.createdAt && likes == other.likes && comments == other.comments && visibility == other.visibility && font == other.font && size == other.size && imageCount == other.imageCount && mediaTypes == other.mediaTypes
    override fun hashCode(): Int = listOf(id, authorId, body, hashtags, createdAt, likes, comments, visibility, font, size, imageCount, mediaTypes).hashCode()

    init {
        require(imageCount in 0..MAX_IMAGES) { "Post must have at most $MAX_IMAGES images" }
        require(mediaTypes.isEmpty() || mediaTypes.size == imageCount) { "Attachment types must match attachment count" }
        require(body.isNotBlank() || imageCount > 0) { "Post body must not be empty" }
        require(body.length <= MAX_BODY_LENGTH) { "Post body must be at most $MAX_BODY_LENGTH characters" }
        require(hashtags == extractHashtags(body)) { "Hashtags must match the post body" }
    }

    companion object {
        const val MAX_BODY_LENGTH = 2_000
        const val MAX_IMAGES = 4

        private val hashtagInBody = Regex("(?<![\\p{L}\\p{M}\\p{N}_/&@#.:])#([\\p{L}\\p{M}\\p{N}_]{1,50})(?![\\p{L}\\p{M}\\p{N}_])")

        fun extractHashtags(body: String): Set<Hashtag> = hashtagInBody
            .findAll(body).map { Hashtag.from(it.groupValues[1]) }.toSet()

        fun publish(authorId: UserId, body: String, now: Instant = Instant.now(), imageCount: Int = 0): Post {
            val clean = body.trim()
            require(clean.isNotEmpty() || imageCount > 0) { "Post body must not be empty" }
            require(clean.length <= MAX_BODY_LENGTH) { "Post body must be at most $MAX_BODY_LENGTH characters" }
            val tags = extractHashtags(clean)
            return Post(PostId.new(), authorId, clean, tags, now, imageCount = imageCount)
        }
    }

    fun like(userId: UserId): Post = copy(likes = likes + userId)
    fun unlike(userId: UserId): Post = copy(likes = likes - userId)

    fun addComment(authorId: UserId, body: String, now: Instant = Instant.now()): Post {
        val clean = body.trim()
        require(clean.isNotEmpty()) { "Comment must not be empty" }
        require(clean.length <= 1_000) { "Comment must be at most 1000 characters" }
        return copy(comments = comments + Comment(authorId = authorId, body = clean, createdAt = now))
    }
}
