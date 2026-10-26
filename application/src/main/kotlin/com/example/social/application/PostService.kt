package com.example.social.application

import com.example.social.domain.*
import java.time.Instant

interface PostRepository {
    fun readable(id: PostId, viewer: UserId?): Boolean = find(id)?.let { it.authorId == viewer || it.visibility == PostVisibility.PUBLIC } == true
    fun visiblePage(limit: Int, before: Instant?, viewer: UserId?): List<Post> =
        page(Int.MAX_VALUE, before).filter { readable(it.id, viewer) }.take(limit)
    fun visibleByHashtag(tag: Hashtag, limit: Int, viewer: UserId?): List<Post> =
        findByHashtag(tag, Int.MAX_VALUE).filter { readable(it.id, viewer) }.take(limit)
    fun changeVisibility(id: PostId, owner: UserId, visibility: PostVisibility): Boolean {
        if (find(id)?.authorId != owner) return false
        return update(id) { it.copy(visibility = visibility) } != null
    }
    fun setLike(id: PostId, user: UserId, liked: Boolean): Boolean =
        update(id) { if (liked) it.like(user) else it.unlike(user) } != null
    fun appendComment(id: PostId, comment: Comment): Boolean =
        update(id) { it.copy(comments = it.comments + comment) } != null
    fun save(post: Post): Post
    fun find(id: PostId): Post?
    fun page(limit: Int, before: Instant? = null): List<Post>
    fun findByHashtag(tag: Hashtag, limit: Int): List<Post>
    fun update(id: PostId, change: (Post) -> Post): Post?
}

class PostService(private val posts: PostRepository) {
    fun publish(author: UserId, body: String, visibility: PostVisibility = PostVisibility.PUBLIC): Post =
        posts.save(Post.publish(author, body).copy(visibility = visibility))

    fun get(id: PostId, viewer: UserId? = null): Post? = if (posts.readable(id, viewer)) posts.find(id) else null
    fun changeVisibility(id: PostId, owner: UserId, visibility: PostVisibility) {
        if (!posts.changeVisibility(id, owner, visibility)) throw NoSuchElementException("Post not found")
    }

    fun feed(limit: Int, before: Instant? = null, viewer: UserId? = null): List<Post> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return posts.visiblePage(limit, before, viewer)
    }

    fun byHashtag(rawTag: String, limit: Int, viewer: UserId? = null): List<Post> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return posts.visibleByHashtag(Hashtag.from(rawTag), limit, viewer)
    }

    fun like(id: PostId, user: UserId): Post =
        react(id, user, true)

    fun unlike(id: PostId, user: UserId): Post =
        react(id, user, false)

    fun comment(id: PostId, author: UserId, body: String): Post {
        addComment(id, author, body)
        return posts.find(id) ?: throw NoSuchElementException("Post not found")
    }

    fun addComment(id: PostId, author: UserId, body: String) {
        if (!posts.readable(id, author)) throw NoSuchElementException("Post not found")
        if (!posts.appendComment(id, Comment(authorId = author, body = body.trim()))) throw NoSuchElementException("Post not found")
    }

    fun setLike(id: PostId, user: UserId, liked: Boolean) {
        if (!posts.readable(id, user)) throw NoSuchElementException("Post not found")
        if (!posts.setLike(id, user, liked)) throw NoSuchElementException("Post not found")
    }

    private fun react(id: PostId, user: UserId, liked: Boolean): Post {
        setLike(id, user, liked)
        return posts.find(id) ?: throw NoSuchElementException("Post not found")
    }
}
