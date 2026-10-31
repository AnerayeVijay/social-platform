package com.example.social.infrastructure

import com.example.social.sharedkernel.EventBus

import com.example.social.application.*
import com.example.social.domain.*
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.*

class PostAudienceTest {
    @Test fun `audiences filter before pagination and follow current approval and owner changes`() {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                val accounts = PostgresAccounts(ds)
                fun member(email: String) = Account(UserId.new(), email, email, null, true).also(accounts::create).id
                val owner = member("owner@example.com")
                val follower = member("follower@example.com")
                val stranger = member("stranger@example.com")
                val follows = FollowService(accounts, PostgresFollows(ds))
                val repository = PostgresPostRepository(ds, EventBus())
                val service = PostService(repository)
                val queries = PostgresPostQueries(ds)
                val public = service.publish(owner, "Public #audience", PostVisibility.PUBLIC)
                val members = service.publish(owner, "Members #audience", PostVisibility.MEMBERS)
                val private = service.publish(owner, "Private #audience", PostVisibility.PRIVATE)
                assertEquals(PostVisibility.MEMBERS, members.visibility)
                assertEquals(PostVisibility.PRIVATE, repository.find(private.id)!!.visibility)
                fun visible(viewer: UserId?) = queries.feed(20, viewer = viewer).posts.map { it.id }.toSet()
                assertEquals(setOf(public.id), visible(null))
                assertEquals(setOf(public.id), visible(stranger))
                assertEquals(setOf(public.id, members.id, private.id), visible(owner))
                follows.request(follower, owner)
                assertEquals(setOf(public.id), visible(follower))
                follows.accept(owner, follower)
                assertEquals(setOf(public.id, members.id), visible(follower))
                assertEquals(setOf(public.id, members.id), queries.followingFeed(follower, 20).posts.map { it.id }.toSet())
                assertEquals(listOf(public.id), queries.feed(1, viewer = stranger, tag = Hashtag("audience"), author = owner).posts.map { it.id })
                assertEquals(listOf(public.id), service.feed(1, viewer = stranger).map { it.id })
                assertEquals(listOf(public.id), service.byHashtag("audience", 1, stranger).map { it.id })
                assertNull(queries.post(private.id, follower))
                assertNull(service.get(members.id, stranger))
                assertFailsWith<NoSuchElementException> { service.setLike(members.id, stranger, true) }
                assertFailsWith<NoSuchElementException> { service.addComment(private.id, follower, "Hidden") }
                service.addComment(members.id, follower, "Allowed")
                val root = repository.find(members.id)!!.comments.single()
                assertTrue(queries.visibleComments(members.id, 20, null, stranger).comments.isEmpty())
                assertEquals(root.id, queries.visibleComments(members.id, 20, null, follower).comments.single().id)
                val threads = CommentThreads(PostgresCommentThreads(ds, EventBus()))
                val reply = threads.reply(members.id, root.id, follower, "Reply")
                assertFailsWith<NoSuchElementException> { threads.page(members.id, null, stranger) }
                assertFailsWith<NoSuchElementException> { threads.get(members.id, root.id, stranger) }
                assertFailsWith<NoSuchElementException> { threads.reply(members.id, root.id, stranger, "Hidden") }
                assertFailsWith<NoSuchElementException> { threads.like(members.id, reply.id, stranger, true) }
                assertFailsWith<NoSuchElementException> { service.changeVisibility(public.id, follower, PostVisibility.PRIVATE) }
                service.changeVisibility(members.id, owner, PostVisibility.PRIVATE)
                assertNull(queries.post(members.id, follower))
                assertFailsWith<NoSuchElementException> { threads.page(members.id, root.id, follower) }
                service.changeVisibility(members.id, owner, PostVisibility.MEMBERS)
                follows.removeFollower(owner, follower)
                assertEquals(setOf(public.id), visible(follower))
                assertFailsWith<NoSuchElementException> { threads.like(members.id, root.id, follower, false) }
                service.changeVisibility(private.id, owner, PostVisibility.PUBLIC)
                assertEquals(setOf(public.id, private.id), visible(null))
            }
        }
    }
}
