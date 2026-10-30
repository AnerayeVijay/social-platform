package com.example.social.infrastructure

import com.example.social.domain.*
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.io.IOException
import kotlin.test.*

class MediaStreamingTest {
    private fun withDb(block: (HikariDataSource) -> Unit) = PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
        database.start()
        HikariDataSource(HikariConfig().apply { jdbcUrl = database.jdbcUrl; username = database.username; password = database.password }).use { ds ->
            Flyway.configure().dataSource(ds).load().migrate()
            block(ds)
        }
    }

    private fun media(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    @Test fun `post media is read lazily so revoked visibility stops the stream`() = withDb { ds ->
        val repository = PostgresPostRepository(ds)
        val owner = UserId.new()
        val post = Post.publish(owner, "", imageCount = 1)
        repository.saveWithImages(post, listOf(PostImage("video/mp4", media(10))))
        val content = repository.imageContent(post.id, 0, null)!!
        repository.changeVisibility(post.id, owner, PostVisibility.PRIVATE)
        assertFailsWith<IOException> { content.open(0, 10).use { it.readBytes() } }
    }

    @Test fun `post media ranges return exact slices across chunk boundaries`() = withDb { ds ->
        val repository = PostgresPostRepository(ds)
        val bytes = media(MEDIA_CHUNK_BYTES * 2 + 123)
        val post = Post.publish(UserId.new(), "", imageCount = 1)
        repository.saveWithImages(post, listOf(PostImage("audio/mpeg", bytes)))
        val content = repository.imageContent(post.id, 0, null)!!
        assertEquals("audio/mpeg", content.mediaType)
        assertEquals(bytes.size.toLong(), content.length)
        assertContentEquals(bytes, content.open(0, content.length).use { it.readBytes() })
        val start = MEDIA_CHUNK_BYTES - 5L
        assertContentEquals(bytes.copyOfRange(start.toInt(), bytes.size - 1), content.open(start, bytes.size - 1 - start).use { it.readBytes() })
        assertContentEquals(ByteArray(0), content.open(bytes.size.toLong(), 0).use { it.readBytes() })
        content.open(10, 3).use { assertEquals(bytes[10].toInt() and 0xff, it.read()); assertEquals(2, it.readNBytes(5).size); assertEquals(-1, it.read()) }
        content.open(0, bytes.size.toLong()).use {
            assertEquals(MEDIA_CHUNK_BYTES + 1L, it.skip(MEDIA_CHUNK_BYTES + 1L))
            assertEquals(bytes[MEDIA_CHUNK_BYTES + 1].toInt() and 0xff, it.read())
            assertEquals(bytes.size - MEDIA_CHUNK_BYTES - 2L, it.skip(Long.MAX_VALUE))
            assertEquals(-1, it.read())
        }
    }

    @Test fun `closed or shrunken streams fail rather than read on`() = withDb { ds ->
        val repository = PostgresPostRepository(ds)
        val bytes = media(MEDIA_CHUNK_BYTES + 10)
        val post = Post.publish(UserId.new(), "", imageCount = 1)
        repository.saveWithImages(post, listOf(PostImage("video/mp4", bytes)))
        val content = repository.imageContent(post.id, 0, null)!!
        val closed = content.open(0, content.length).apply { read(); close() }
        assertFailsWith<IOException> { closed.read() }
        content.open(0, content.length).use { stream ->
            stream.readNBytes(MEDIA_CHUNK_BYTES)
            ds.connection.use { it.createStatement().executeUpdate("UPDATE post_images SET bytes=substring(bytes FROM 1 FOR ${MEDIA_CHUNK_BYTES + 2})") }
            assertFailsWith<IOException> { stream.readBytes() }
        }
    }

    @Test fun `comment and reply media stream with post match and audience enforced`() = withDb { ds ->
        val repository = PostgresPostRepository(ds)
        val threads = PostgresCommentThreads(ds)
        val owner = UserId.new()
        val follower = UserId.new()
        val post = repository.save(Post.publish(owner, "p"))
        val other = repository.save(Post.publish(owner, "q"))
        val bytes = media(MEDIA_CHUNK_BYTES + 77)
        val root = Comment(authorId = owner, body = "", attachmentType = "video/webm")
        assertTrue(repository.appendCommentWithAttachment(post.id, root, PostImage("video/webm", bytes)))
        val reply = Comment(authorId = owner, body = "", parentId = root.id, attachmentType = "audio/ogg")
        assertTrue(threads.appendWithAttachment(post.id, reply, PostImage("audio/ogg", bytes.copyOf(5))))
        val viaPosts = repository.commentAttachmentContent(post.id, root.id, null)!!
        assertEquals("video/webm" to bytes.size.toLong(), viaPosts.mediaType to viaPosts.length)
        assertContentEquals(bytes, viaPosts.open(0, viaPosts.length).use { it.readBytes() })
        val viaThreads = threads.attachmentContent(post.id, reply.id, null)!!
        assertEquals("audio/ogg" to 5L, viaThreads.mediaType to viaThreads.length)
        assertContentEquals(bytes.copyOf(5), viaThreads.open(0, 5).use { it.readBytes() })
        assertNull(repository.commentAttachmentContent(other.id, root.id, owner))
        assertNull(threads.attachmentContent(other.id, reply.id, owner))
        repository.changeVisibility(post.id, owner, PostVisibility.MEMBERS)
        assertNull(threads.attachmentContent(post.id, reply.id, follower))
        assertFailsWith<IOException> { viaThreads.open(0, 5).use { it.readBytes() } }
        assertFailsWith<IOException> { viaPosts.open(0, 5).use { it.readBytes() } }
        assertNotNull(repository.commentAttachmentContent(post.id, root.id, owner))
        assertNotNull(threads.attachmentContent(post.id, root.id, owner))
    }

    @Test fun `media bytes use uncompressed toast storage so ranges fetch only needed slices`() = withDb { ds ->
        val storage = ds.connection.use { c ->
            c.createStatement().executeQuery("SELECT attrelid::regclass::text, attstorage FROM pg_attribute WHERE attname='bytes' AND attrelid IN ('post_images'::regclass,'comment_media'::regclass) ORDER BY 1").use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
            }
        }
        assertEquals(mapOf("comment_media" to "e", "post_images" to "e"), storage)
    }

    @Test fun `post media is hidden from outsiders and wrong positions`() = withDb { ds ->
        val repository = PostgresPostRepository(ds)
        val owner = UserId.new()
        val post = Post.publish(owner, "", imageCount = 1).copy(visibility = PostVisibility.PRIVATE)
        repository.saveWithImages(post, listOf(PostImage("image/png", media(3))))
        assertNull(repository.imageContent(post.id, 0, UserId.new()))
        assertNull(repository.imageContent(post.id, 1, owner))
        assertEquals(3L, repository.imageContent(post.id, 0, owner)!!.length)
    }
}
