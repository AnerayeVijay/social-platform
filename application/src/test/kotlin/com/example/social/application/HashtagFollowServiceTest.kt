package com.example.social.application

import com.example.social.domain.Hashtag
import com.example.social.domain.UserId
import kotlin.test.Test
import kotlin.test.assertEquals

class HashtagFollowServiceTest {
    private val alice = UserId.new()
    private val follows = MemoryHashtagFollows()
    private val service = HashtagFollowService(follows)

    @Test fun `following a hashtag is idempotent and unfollowing removes it`() {
        service.follow(alice, Hashtag.from("#Kotlin"))
        service.follow(alice, Hashtag.from("kotlin"))
        assertEquals(listOf(Hashtag("kotlin")), service.followed(alice))

        service.unfollow(alice, Hashtag("kotlin"))
        service.unfollow(alice, Hashtag("kotlin"))
        assertEquals(emptyList(), service.followed(alice))
    }
}

private class MemoryHashtagFollows : HashtagFollows {
    private val rows = mutableSetOf<Pair<UserId, Hashtag>>()
    override fun add(member: UserId, tag: Hashtag) { rows.add(member to tag) }
    override fun remove(member: UserId, tag: Hashtag) { rows.remove(member to tag) }
    override fun list(member: UserId): List<Hashtag> = rows.filter { it.first == member }.map { it.second }.sortedBy { it.value }
}
