package com.example.social.infrastructure

import com.example.social.domain.PostImage
import com.example.social.domain.Comment
import com.example.social.domain.CommentId
import com.example.social.domain.PostId
import com.example.social.domain.UserId
import com.example.social.application.MediaContent
import java.sql.Connection
import javax.sql.DataSource

internal const val ATTACHMENT_TYPE_SQL = "(SELECT m.media_type FROM comment_media m WHERE m.comment_id=c.id) AS attachment_type"

/** Comments carrying media metadata must go through the attachment path so the claim is backed by bytes. */
internal fun requireUnattached(comment: Comment) =
    require(comment.attachmentType == null) { "Comment attachment type must be backed by an attachment" }

internal fun requireBacks(comment: Comment, attachment: PostImage?) =
    require(comment.attachmentType == attachment?.mediaType) { "Comment attachment type must match its attachment" }

internal fun insertCommentMedia(c: Connection, id: CommentId, attachment: PostImage) {
    c.prepareStatement("INSERT INTO comment_media(comment_id,media_type,bytes) VALUES (?,?,?)").use {
        it.setObject(1, id.value); it.setString(2, attachment.mediaType); it.setBytes(3, attachment.bytes); it.executeUpdate()
    }
}

internal fun readCommentMedia(c: Connection, post: PostId, id: CommentId, viewer: UserId?): PostImage? =
    c.prepareStatement("SELECT m.media_type,m.bytes FROM comment_media m JOIN comments c ON c.id=m.comment_id JOIN posts p ON p.id=c.post_id WHERE c.post_id=? AND c.id=? AND ${postVisibilitySql()}").use {
        it.setObject(1, post.value); it.setObject(2, id.value); it.setObject(3, viewer?.value)
        it.executeQuery().use { rows -> if (rows.next()) PostImage(rows.getString(1), rows.getBytes(2)) else null }
    }

internal fun commentMediaContent(dataSource: DataSource, post: PostId, id: CommentId, viewer: UserId?): MediaContent? =
    PostgresMediaContent(dataSource, "m", "FROM comment_media m JOIN comments c ON c.id=m.comment_id JOIN posts p ON p.id=c.post_id WHERE c.post_id=? AND c.id=? AND ${postVisibilitySql()}") { ps, at ->
        ps.setObject(at, post.value); ps.setObject(at + 1, id.value); ps.setObject(at + 2, viewer?.value)
    }.load()
