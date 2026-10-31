package com.example.social.infrastructure

import com.example.social.application.*
import com.example.social.domain.*
import com.example.social.notifications.*
import com.example.social.sharedkernel.EventBus
import com.zaxxer.hikari.*
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.*

class PostgresNotificationsTest {
    @Test fun `follow request notifies only its recipient with the requester name`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val a = Account(UserId.new(), "a@example.com", "Ada", null, true).also(accounts::create).id
        val b = Account(UserId.new(), "b@example.com", "Bo", null, true).also(accounts::create).id
        val notifications = Notifications(PostgresNotifications(ds))

        FollowService(accounts, PostgresFollows(ds)).request(a, b)

        val received = notifications.list(b)
        assertEquals(listOf(NotificationKind.FOLLOW_REQUEST to (a to "Ada")), received.map { it.kind to (it.actor to it.actorName) })
        assertFalse(received.single().read)
        assertEquals(1L, notifications.unreadCount(b))
        assertEquals(emptyList(), notifications.list(a))
        assertEquals(0L, notifications.unreadCount(a))
    }

    @Test fun `cancelled or rejected follow requests withdraw their notice and a new request notifies once`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val a = Account(UserId.new(), "a@example.com", "Ada", null, true).also(accounts::create).id
        val b = Account(UserId.new(), "b@example.com", "Bo", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))

        follows.request(a, b)
        follows.cancel(a, b)
        assertEquals(0L, notifications.unreadCount(b))

        follows.request(a, b)
        follows.request(a, b)
        assertEquals(1, notifications.list(b).size)
        follows.reject(b, a)
        assertEquals(emptyList(), notifications.list(b))

        follows.request(a, b)
        assertEquals(1L, notifications.unreadCount(b))
    }

    @Test fun `a failed notice write rolls back the follow request and a failed withdrawal keeps the request`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val a = Account(UserId.new(), "a@example.com", "Ada", null, true).also(accounts::create).id
        val b = Account(UserId.new(), "b@example.com", "Bo", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))
        ds.connection.use { it.createStatement().execute("""
            CREATE FUNCTION fail_notice() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'notice write failed'; END ${'$'}${'$'};
            CREATE TRIGGER fail_notice BEFORE INSERT OR DELETE ON notifications FOR EACH ROW EXECUTE FUNCTION fail_notice();
        """) }

        assertFails { follows.request(a, b) }
        assertEquals(FollowState.NONE, follows.state(a, b))

        ds.connection.use { it.createStatement().execute("ALTER TABLE notifications DISABLE TRIGGER fail_notice") }
        follows.request(a, b)
        ds.connection.use { it.createStatement().execute("ALTER TABLE notifications ENABLE TRIGGER fail_notice") }
        assertFails { follows.cancel(a, b) }
        assertEquals(FollowState.PENDING, follows.state(a, b))
        assertEquals(1L, notifications.unreadCount(b))
    }

    @Test fun `accepting a request replaces the incoming notice with an accepted notice for the requester`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val a = Account(UserId.new(), "a@example.com", "Ada", null, true).also(accounts::create).id
        val b = Account(UserId.new(), "b@example.com", "Bo", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))

        follows.request(a, b)
        follows.accept(b, a)

        assertEquals(FollowState.ACCEPTED, follows.state(a, b))
        assertEquals(emptyList(), notifications.list(b))
        assertEquals(listOf(NotificationKind.FOLLOW_ACCEPTED to (b to "Bo")), notifications.list(a).map { it.kind to (it.actor to it.actorName) })
        assertEquals(1L, notifications.unreadCount(a))
    }

    @Test fun `only a real pending to accepted transition notifies and each new follow lifecycle adds history`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val a = Account(UserId.new(), "a@example.com", "Ada", null, true).also(accounts::create).id
        val b = Account(UserId.new(), "b@example.com", "Bo", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))

        follows.accept(b, a)
        follows.request(a, b)
        follows.cancel(a, b)
        follows.accept(b, a)
        assertEquals(emptyList(), notifications.list(a))

        follows.request(a, b)
        follows.accept(b, a)
        follows.accept(b, a)
        assertEquals(1, notifications.list(a).size)

        follows.unfollow(a, b)
        follows.request(a, b)
        follows.accept(b, a)
        assertEquals(listOf(NotificationKind.FOLLOW_ACCEPTED, NotificationKind.FOLLOW_ACCEPTED), notifications.list(a).map { it.kind })
        assertEquals(2L, notifications.unreadCount(a))
        assertEquals(emptyList(), notifications.list(b))
    }

    @Test fun `a failed accepted notice write rolls back the acceptance and keeps the pending notice`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val a = Account(UserId.new(), "a@example.com", "Ada", null, true).also(accounts::create).id
        val b = Account(UserId.new(), "b@example.com", "Bo", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))
        follows.request(a, b)
        ds.connection.use { it.createStatement().execute("""
            CREATE FUNCTION fail_notice() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'notice write failed'; END ${'$'}${'$'};
            CREATE TRIGGER fail_notice BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION fail_notice();
        """) }

        assertFails { follows.accept(b, a) }

        assertEquals(FollowState.PENDING, follows.state(a, b))
        assertEquals(listOf(NotificationKind.FOLLOW_REQUEST), notifications.list(b).map { it.kind })
        assertEquals(emptyList(), notifications.list(a))
    }

    @Test fun `publishing a post notifies an accepted follower with the post reference`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val a = Account(UserId.new(), "a@example.com", "Ada", null, true).also(accounts::create).id
        val b = Account(UserId.new(), "b@example.com", "Bo", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))
        follows.request(a, b)
        follows.accept(b, a)

        val post = wiredPosts(ds).save(Post.publish(b, "Hello followers"))

        assertEquals(NotificationKind.NEW_POST to (b to post.id), notifications.list(a).first().let { it.kind to (it.actor to it.post) })
        assertEquals(2L, notifications.unreadCount(a))
    }

    @Test fun `new post notices reach only accepted followers allowed to see the post`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val follower = Account(UserId.new(), "f@example.com", "Follower", null, true).also(accounts::create).id
        val pending = Account(UserId.new(), "p@example.com", "Pending", null, true).also(accounts::create).id
        val stranger = Account(UserId.new(), "s@example.com", "Stranger", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))
        val posts = wiredPosts(ds)
        follows.request(follower, author); follows.accept(author, follower)
        follows.request(pending, author)

        posts.save(Post.publish(author, "Secret").copy(visibility = PostVisibility.PRIVATE))
        val members = posts.save(Post.publish(author, "Members").copy(visibility = PostVisibility.MEMBERS))
        val public = posts.saveWithImages(Post.publish(author, "", imageCount = 1).copy(visibility = PostVisibility.PUBLIC), listOf(PostImage("image/png", byteArrayOf(1))))

        assertEquals(listOf(public.id, members.id), notifications.list(follower).filter { it.kind == NotificationKind.NEW_POST }.map { it.post })
        assertEquals(emptyList(), notifications.list(pending).filter { it.kind == NotificationKind.NEW_POST })
        assertEquals(emptyList(), notifications.list(stranger))
        assertEquals(listOf(NotificationKind.FOLLOW_REQUEST), notifications.list(author).map { it.kind })
    }

    @Test fun `a failed new post notice rolls back the post and its images`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val follower = Account(UserId.new(), "f@example.com", "Follower", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        follows.request(follower, author); follows.accept(author, follower)
        ds.connection.use { it.createStatement().execute("""
            CREATE FUNCTION fail_notice() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'notice write failed'; END ${'$'}${'$'};
            CREATE TRIGGER fail_notice BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION fail_notice();
        """) }
        val posts = wiredPosts(ds)
        val post = Post.publish(author, "", imageCount = 1)

        assertFails { posts.saveWithImages(post, listOf(PostImage("image/png", byteArrayOf(1)))) }

        assertNull(posts.find(post.id))
        assertNull(posts.image(post.id, 0, author))
        assertEquals(listOf(NotificationKind.FOLLOW_ACCEPTED), Notifications(PostgresNotifications(ds)).list(follower).map { it.kind })
    }

    @Test fun `post notices follow current post visibility in both list and unread count`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val follower = Account(UserId.new(), "f@example.com", "Follower", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))
        val posts = wiredPosts(ds)
        follows.request(follower, author); follows.accept(author, follower)
        val public = posts.save(Post.publish(author, "Public"))
        val members = posts.save(Post.publish(author, "Members").copy(visibility = PostVisibility.MEMBERS))
        val later = posts.save(Post.publish(author, "Later"))
        fun postsSeen() = notifications.list(follower).mapNotNull { it.post }
        assertEquals(4L, notifications.unreadCount(follower))

        posts.changeVisibility(later.id, author, PostVisibility.PRIVATE)
        assertEquals(listOf(members.id, public.id), postsSeen())
        assertEquals(3L, notifications.unreadCount(follower))

        follows.unfollow(follower, author)
        assertEquals(listOf(public.id), postsSeen())
        assertEquals(listOf(NotificationKind.NEW_POST, NotificationKind.FOLLOW_ACCEPTED), notifications.list(follower).map { it.kind })
        assertEquals(2L, notifications.unreadCount(follower))

        posts.changeVisibility(later.id, author, PostVisibility.PUBLIC)
        assertEquals(listOf(later.id, public.id), postsSeen())
    }

    @Test fun `liking a post notifies its author once and unliking withdraws the notice`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val fan = Account(UserId.new(), "fan@example.com", "Fan", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val notifications = Notifications(PostgresNotifications(ds))
        val post = posts.save(Post.publish(author, "Like me"))
        fun likeNotices() = notifications.list(author).filter { it.kind == NotificationKind.POST_LIKE }.map { it.actor to it.post }

        assertTrue(posts.setLike(post.id, fan, true))

        assertEquals(listOf(fan to post.id), likeNotices())
        assertEquals(1L, notifications.unreadCount(author))
    }

    @Test fun `a like reaches its author through the event bus once the like is committed`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val fan = Account(UserId.new(), "fan@example.com", "Fan", null, true).also(accounts::create).id
        val events = EventBus().also { NotificationHandlers(ds).register(it) }
        val posts = PostgresPostRepository(ds, events)
        val notifications = Notifications(PostgresNotifications(ds))
        val post = posts.save(Post.publish(author, "Like me"))

        assertTrue(posts.setLike(post.id, fan, true))

        assertEquals(listOf(fan to post.id), notifications.list(author).filter { it.kind == NotificationKind.POST_LIKE }.map { it.actor to it.post })
    }

    @Test fun `only real like transitions change notices and self likes stay silent`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val fan = Account(UserId.new(), "fan@example.com", "Fan", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val notifications = Notifications(PostgresNotifications(ds))
        val post = posts.save(Post.publish(author, "Like me"))
        fun likeNotices() = notifications.list(author).filter { it.kind == NotificationKind.POST_LIKE }

        assertTrue(posts.setLike(post.id, author, true))
        assertEquals(setOf(author), posts.find(post.id)!!.likes)
        assertEquals(emptyList(), likeNotices())

        posts.setLike(post.id, fan, true)
        posts.setLike(post.id, fan, true)
        assertEquals(1, likeNotices().size)

        assertTrue(posts.setLike(post.id, fan, false))
        assertTrue(posts.setLike(post.id, fan, false))
        assertEquals(emptyList(), likeNotices())
        assertEquals(setOf(author), posts.find(post.id)!!.likes)

        posts.setLike(post.id, fan, true)
        assertEquals(listOf(fan to false), likeNotices().map { it.actor to it.read })
        assertEquals(1L, notifications.unreadCount(author))
    }

    @Test fun `a failed like notice write leaves the like in place without a notice`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val fan = Account(UserId.new(), "fan@example.com", "Fan", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val notifications = Notifications(PostgresNotifications(ds))
        val post = posts.save(Post.publish(author, "Like me"))
        ds.connection.use { it.createStatement().execute("""
            CREATE FUNCTION fail_notice() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'notice write failed'; END ${'$'}${'$'};
            CREATE TRIGGER fail_notice BEFORE INSERT OR DELETE ON notifications FOR EACH ROW EXECUTE FUNCTION fail_notice();
        """) }

        assertTrue(posts.setLike(post.id, fan, true))

        assertEquals(setOf(fan), posts.find(post.id)!!.likes)
        assertEquals(0L, notifications.unreadCount(author))
    }

    @Test fun `a failed like notice is logged with its cause`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val fan = Account(UserId.new(), "fan@example.com", "Fan", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val post = posts.save(Post.publish(author, "Like me"))
        ds.connection.use { it.createStatement().execute("""
            CREATE FUNCTION fail_notice() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'notice write failed'; END ${'$'}${'$'};
            CREATE TRIGGER fail_notice BEFORE INSERT OR DELETE ON notifications FOR EACH ROW EXECUTE FUNCTION fail_notice();
        """) }
        val records = mutableListOf<LogRecord>()
        val logger = Logger.getLogger(PostgresPostRepository::class.java.name)
        val capture = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(capture)
        try {
            posts.setLike(post.id, fan, true)
        } finally {
            logger.removeHandler(capture)
        }

        val warning = records.single { it.level == Level.WARNING }
        assertEquals(true, warning.thrown?.message?.contains("notice write failed"))
    }

    @Test fun `a failed withdrawal keeps the like and its notice`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val fan = Account(UserId.new(), "fan@example.com", "Fan", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val notifications = Notifications(PostgresNotifications(ds))
        val post = posts.save(Post.publish(author, "Like me"))
        ds.connection.use { it.createStatement().execute("""
            CREATE FUNCTION fail_notice() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'notice write failed'; END ${'$'}${'$'};
            CREATE TRIGGER fail_notice BEFORE INSERT OR DELETE ON notifications FOR EACH ROW EXECUTE FUNCTION fail_notice();
        """) }

        ds.connection.use { it.createStatement().execute("ALTER TABLE notifications DISABLE TRIGGER fail_notice") }
        assertTrue(posts.setLike(post.id, fan, true))
        ds.connection.use { it.createStatement().execute("ALTER TABLE notifications ENABLE TRIGGER fail_notice") }
        assertEquals(1L, notifications.unreadCount(author))

        assertFails { posts.setLike(post.id, fan, false) }
        assertEquals(setOf(fan), posts.find(post.id)!!.likes)
        assertEquals(1L, notifications.unreadCount(author))
    }

    @Test fun `concurrent likes and unlikes leave exactly one notice per final like`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val fan = Account(UserId.new(), "fan@example.com", "Fan", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val notifications = Notifications(PostgresNotifications(ds))
        val post = posts.save(Post.publish(author, "Race me"))
        fun race(first: Boolean, second: Boolean) {
            val barrier = CyclicBarrier(2)
            Executors.newFixedThreadPool(2).use { workers ->
                listOf(first, second).map { liked -> workers.submit(Callable { barrier.await(5, TimeUnit.SECONDS); posts.setLike(post.id, fan, liked) }) }
                    .forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
            }
            val notices = notifications.list(author).filter { it.kind == NotificationKind.POST_LIKE }
            assertTrue(notices.all { it.actor == fan && it.post == post.id })
            assertEquals(if (fan in posts.find(post.id)!!.likes) 1 else 0, notices.size)
        }

        repeat(25) {
            posts.setLike(post.id, fan, false)
            race(true, true)
            assertEquals(setOf(fan), posts.find(post.id)!!.likes)
            race(true, false)
            race(false, true)
            posts.setLike(post.id, fan, true)
            race(false, true)
        }
    }

    @Test fun `liking a post the member cannot see neither likes nor notifies`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val stranger = Account(UserId.new(), "s@example.com", "Stranger", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val post = posts.save(Post.publish(author, "Members only").copy(visibility = PostVisibility.MEMBERS))

        assertFalse(posts.setLike(post.id, stranger, true))

        assertEquals(emptySet(), posts.find(post.id)!!.likes)
        assertEquals(0L, Notifications(PostgresNotifications(ds)).unreadCount(author))
    }

    @Test fun `commenting on a post notifies its owner with the comment reference`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true).also(accounts::create).id
        val reader = Account(UserId.new(), "reader@example.com", "Reader", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val notifications = Notifications(PostgresNotifications(ds))
        val post = posts.save(Post.publish(owner, "Talk to me"))
        val comment = Comment(authorId = reader, body = "Hello")

        assertTrue(posts.appendComment(post.id, comment))

        assertEquals(listOf(Triple(reader, post.id, comment.id)), notifications.list(owner).map { Triple(it.actor, it.post, it.comment) })
        assertEquals(NotificationKind.COMMENT, notifications.list(owner).single().kind)
        assertEquals(1L, notifications.unreadCount(owner))
    }

    @Test fun `replies notify only the post owner and own comments stay silent`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true).also(accounts::create).id
        val reader = Account(UserId.new(), "reader@example.com", "Reader", null, true).also(accounts::create).id
        val other = Account(UserId.new(), "other@example.com", "Other", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val threads = PostgresCommentThreads(ds)
        val notifications = Notifications(PostgresNotifications(ds))
        val post = posts.save(Post.publish(owner, "Talk to me"))
        val own = Comment(authorId = owner, body = "First")
        posts.appendComment(post.id, own)
        val readerComment = Comment(authorId = reader, body = "Top")
        posts.appendComment(post.id, readerComment)

        val reply = Comment(authorId = other, body = "Reply", parentId = readerComment.id)
        assertTrue(threads.append(post.id, reply))
        assertTrue(threads.append(post.id, Comment(authorId = owner, body = "Owner reply", parentId = readerComment.id)))

        assertEquals(listOf(other to reply.id, reader to readerComment.id), notifications.list(owner).map { it.actor to it.comment })
        assertEquals(emptyList(), notifications.list(reader))
        assertEquals(emptyList(), notifications.list(other))
    }

    @Test fun `comments on posts the member cannot see neither save nor notify`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true).also(accounts::create).id
        val stranger = Account(UserId.new(), "s@example.com", "Stranger", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val threads = PostgresCommentThreads(ds)
        val post = posts.save(Post.publish(owner, "Members only").copy(visibility = PostVisibility.MEMBERS))
        val ownComment = Comment(authorId = owner, body = "Mine")
        posts.appendComment(post.id, ownComment)

        assertFalse(posts.appendComment(post.id, Comment(authorId = stranger, body = "Hi")))
        assertFalse(threads.append(post.id, Comment(authorId = stranger, body = "Hi", parentId = ownComment.id)))

        assertEquals(listOf(ownComment.id), posts.find(post.id)!!.comments.map { it.id })
        assertEquals(0L, Notifications(PostgresNotifications(ds)).unreadCount(owner))
    }

    @Test fun `a failed comment notice rolls back top level comments and replies`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true).also(accounts::create).id
        val reader = Account(UserId.new(), "reader@example.com", "Reader", null, true).also(accounts::create).id
        val posts = wiredPosts(ds)
        val threads = PostgresCommentThreads(ds)
        val post = posts.save(Post.publish(owner, "Talk to me"))
        val ownComment = Comment(authorId = owner, body = "Mine")
        posts.appendComment(post.id, ownComment)
        ds.connection.use { it.createStatement().execute("""
            CREATE FUNCTION fail_notice() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'notice write failed'; END ${'$'}${'$'};
            CREATE TRIGGER fail_notice BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION fail_notice();
        """) }

        assertFails { posts.appendComment(post.id, Comment(authorId = reader, body = "Top")) }
        assertFails { threads.append(post.id, Comment(authorId = reader, body = "Reply", parentId = ownComment.id)) }

        assertEquals(listOf(ownComment.id), posts.find(post.id)!!.comments.map { it.id })
        assertEquals(0L, Notifications(PostgresNotifications(ds)).unreadCount(owner))
    }

    @Test fun `history pages newest first by id without gaps for tied timestamps`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true).also(accounts::create).id
        val fans = (1..5).map { Account(UserId.new(), "$it@example.com", "Fan $it", null, true).also(accounts::create).id }
        val tied = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val follows = FollowService(accounts, PostgresFollows(ds)) { tied }
        val notifications = Notifications(PostgresNotifications(ds))
        fans.forEach { follows.request(it, owner) }

        val first = notifications.page(owner, limit = 2)
        val second = notifications.page(owner, limit = 2, before = first.next)
        val last = notifications.page(owner, limit = 2, before = second.next)

        assertEquals(fans.reversed(), (first.notifications + second.notifications + last.notifications).map { it.actor })
        assertNull(last.next)
        assertEquals(second, notifications.page(owner, limit = 2, before = first.next))
    }

    @Test fun `history rejects invalid limits and cursors and a foreign cursor only bounds the owner's own history`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true).also(accounts::create).id
        val other = Account(UserId.new(), "other@example.com", "Other", null, true).also(accounts::create).id
        val fan = Account(UserId.new(), "fan@example.com", "Fan", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))
        follows.request(fan, owner)
        follows.request(fan, other)
        val foreign = notifications.list(other).single().id

        assertFailsWith<IllegalArgumentException> { notifications.page(owner, limit = 0) }
        assertFailsWith<IllegalArgumentException> { notifications.page(owner, limit = 101) }
        assertFailsWith<IllegalArgumentException> { notifications.page(owner, before = 0) }
        assertEquals(listOf(fan), notifications.page(owner, limit = 100).notifications.map { it.actor })
        assertEquals(listOf(fan), notifications.page(owner, before = foreign).notifications.map { it.actor })
        assertEquals(emptyList(), notifications.page(other, before = foreign).notifications)
    }

    @Test fun `marking one notice read affects only the owner's notice and is idempotent`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true).also(accounts::create).id
        val other = Account(UserId.new(), "other@example.com", "Other", null, true).also(accounts::create).id
        val fan = Account(UserId.new(), "fan@example.com", "Fan", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))
        follows.request(fan, owner)
        follows.request(other, owner)
        follows.request(fan, other)
        val (newest, oldest) = notifications.list(owner)
        val foreign = notifications.list(other).single()
        assertEquals(2L, notifications.unreadCount(owner))

        val marked = notifications.markRead(owner, oldest.id)

        assertEquals(oldest.copy(read = true), marked)
        assertEquals(listOf(false, true), notifications.list(owner).map { it.read })
        assertEquals(1L, notifications.unreadCount(owner))
        assertEquals(marked, notifications.markRead(owner, oldest.id))
        assertNull(notifications.markRead(owner, foreign.id))
        assertNull(notifications.markRead(owner, Long.MAX_VALUE))
        assertEquals(1L, notifications.unreadCount(other))
        assertFalse(notifications.list(owner).first { it.id == newest.id }.read)
    }

    @Test fun `marking read never touches notices for posts the recipient cannot currently see`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val author = Account(UserId.new(), "author@example.com", "Author", null, true).also(accounts::create).id
        val follower = Account(UserId.new(), "f@example.com", "Follower", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))
        val posts = wiredPosts(ds)
        follows.request(follower, author); follows.accept(author, follower)
        posts.save(Post.publish(author, "Shown"))
        val hidden = posts.save(Post.publish(author, "Hidden later"))
        val hiddenNotice = notifications.list(follower).first { it.post == hidden.id }
        posts.changeVisibility(hidden.id, author, PostVisibility.PRIVATE)

        assertNull(notifications.markRead(follower, hiddenNotice.id))
        notifications.markAllRead(follower)

        assertEquals(listOf(true, true), notifications.list(follower).map { it.read })
        assertEquals(0L, notifications.unreadCount(follower))
        posts.changeVisibility(hidden.id, author, PostVisibility.PUBLIC)
        assertEquals(listOf(hiddenNotice), notifications.list(follower).filter { !it.read })
        assertEquals(1L, notifications.unreadCount(follower))
    }

    @Test fun `mark all leaves a notice committed after it started unread`() = withDatabase { ds ->
        val accounts = PostgresAccounts(ds)
        val owner = Account(UserId.new(), "owner@example.com", "Owner", null, true).also(accounts::create).id
        val early = Account(UserId.new(), "early@example.com", "Early", null, true).also(accounts::create).id
        val late = Account(UserId.new(), "late@example.com", "Late", null, true).also(accounts::create).id
        val follows = FollowService(accounts, PostgresFollows(ds))
        val notifications = Notifications(PostgresNotifications(ds))
        follows.request(early, owner)

        ds.connection.use { pending ->
            pending.autoCommit = false
            pending.prepareStatement("INSERT INTO notifications(recipient_id, actor_id, kind, created_at) VALUES (?, ?, 'follow_request', now())").use {
                it.setObject(1, owner.value); it.setObject(2, late.value); it.executeUpdate()
            }
            Executors.newSingleThreadExecutor().use { worker ->
                worker.submit { notifications.markAllRead(owner) }.get(10, TimeUnit.SECONDS)
            }
            pending.commit()
        }

        assertEquals(listOf(late to false, early to true), notifications.list(owner).map { it.actor to it.read })
        assertEquals(1L, notifications.unreadCount(owner))
    }

    private fun wiredPosts(ds: HikariDataSource) = PostgresPostRepository(ds, EventBus().also { NotificationHandlers(ds).register(it) })

    private fun withDatabase(block: (HikariDataSource) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { database ->
            database.start()
            HikariDataSource(HikariConfig().apply {
                jdbcUrl = database.jdbcUrl; username = database.username; password = database.password
            }).use { ds ->
                Flyway.configure().dataSource(ds).load().migrate()
                block(ds)
            }
        }
    }
}
