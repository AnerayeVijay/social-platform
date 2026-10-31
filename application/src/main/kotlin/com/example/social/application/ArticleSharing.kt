package com.example.social.application

import com.example.social.domain.Post
import com.example.social.domain.PostVisibility
import com.example.social.domain.UserId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

interface ArticleLookup {
    /** The article, only when it belongs to a source owned by [viewer]. */
    fun find(viewer: UserId, id: UUID): FeedArticle?
    /** Any article of [viewer]'s sources whose [articleThreadKey] is [key]. */
    fun findByThreadKey(viewer: UserId, key: String): FeedArticle?
}

class ArticleSharing(private val lookup: ArticleLookup, private val posts: PostService, baseUrl: String) {
    private val discussionBase = baseUrl.trimEnd('/') + "/discussions?url="

    fun share(viewer: UserId, articleId: UUID, visibility: PostVisibility): Post {
        val article = lookup.find(viewer, articleId) ?: throw ArticleNotFound()
        val withoutAbstract = body(article, "")
        val room = (Post.MAX_BODY_LENGTH - withoutAbstract.length - SEPARATOR.length).coerceIn(0, MAX_ABSTRACT)
        return posts.publish(viewer, body(article, cap(plain(article.abstract), room)), visibility)
    }

    /** The viewer's own article for a stable discussion link. */
    fun discussionArticle(viewer: UserId, url: String): FeedArticle = lookup.findByThreadKey(viewer, articleThreadKey(url)) ?: throw ArticleNotFound()

    private fun body(article: FeedArticle, abstract: String) = listOfNotNull(
        plain(article.title).take(MAX_TITLE), "Source: ${plain(article.sourceName)}", abstract.ifEmpty { null }, "Read: ${article.url}",
        "Discussion: " + discussionBase + URLEncoder.encode(article.url, UTF_8)
    ).joinToString(SEPARATOR)

    /** Hashes would turn article text into hashtags on the shared post. */
    private fun plain(text: String) = text.replace("#", "").replace(Regex("\\s+"), " ").trim()

    private fun cap(text: String, room: Int) = if (text.length <= room) text else if (room == 0) "" else text.take(room - 1).trimEnd() + "…"

    companion object { private const val SEPARATOR = "\n\n"
        const val MAX_TITLE = 200; const val MAX_ABSTRACT = 500 }
}
