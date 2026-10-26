package com.example.social.application

import com.example.social.domain.UserId
import java.time.Instant
import kotlin.test.*

class FollowServiceTest {
    private val alice = Account(UserId.new(), "alice@example.com", "Alice", null, true)
    private val bob = Account(UserId.new(), "bob@example.com", "Bob", null, true)
    private val data = mutableMapOf(alice.id to alice, bob.id to bob)
    private val accounts = object : Accounts {
        override fun searchMembers(name: String, excluding: UserId, limit: Int, after: UserId?) = data.values
            .filter { it.verified && it.id != excluding && it.displayName.contains(name, ignoreCase = true) }
            .sortedBy { it.id.value.toString() }
            .filter { after == null || it.id.value.toString() > after.value.toString() }
            .take(limit).map { FollowMember(it.id, it.displayName) }
        override fun byId(id: UserId) = data[id]
        override fun byEmail(email: String) = data.values.find { it.email == email }
        override fun create(account: Account) { data[account.id] = account }
        override fun update(account: Account) { data[account.id] = account }
        override fun putToken(token: AccountToken) = Unit
        override fun consumeToken(digest: String, purpose: String, now: Instant): UserId? = null
    }
    private val follows = MemoryFollows()
    private val service = FollowService(accounts, follows)

    @Test fun `verified members search trimmed names with bounded pages and no blank directory`() {
        val members = (1..21).map {
            Account(UserId.parse("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}"), "member$it@example.com", "Member $it", null, true)
                .also(accounts::create)
        }
        val first = service.search(alice.id, "  mEmBeR  ")
        assertEquals(members.take(20).map { FollowMember(it.id, it.displayName) }, first.members)
        assertEquals(members[19].id, first.next)
        val last = service.search(alice.id, "member", after = first.next)
        assertEquals(listOf(FollowMember(members.last().id, "Member 21")), last.members)
        assertNull(last.next)
        assertEquals(FollowPage(emptyList(), null), service.search(alice.id, "  "))
        assertTrue(service.search(alice.id, "Alice").members.isEmpty())
        accounts.update(bob.copy(verified = false))
        assertTrue(service.search(alice.id, "Bob").members.isEmpty())
        assertFailsWith<IllegalArgumentException> { service.search(bob.id, "member") }
        assertFailsWith<IllegalArgumentException> { service.search(UserId.new(), "member") }
        assertFailsWith<IllegalArgumentException> { service.search(alice.id, "member", limit = 0) }
        assertFailsWith<IllegalArgumentException> { service.search(alice.id, "member", limit = 101) }
        assertFailsWith<IllegalArgumentException> { service.search(alice.id, "x".repeat(81)) }
    }

    @Test fun `stale cancellation rejection and removal forms do not change a different relationship state`() {
        service.request(alice.id, bob.id)
        service.removeFollower(bob.id, alice.id)
        assertEquals(FollowState.PENDING, service.state(alice.id, bob.id))
        service.accept(bob.id, alice.id)
        service.cancel(alice.id, bob.id)
        service.reject(bob.id, alice.id)
        assertEquals(FollowState.ACCEPTED, service.state(alice.id, bob.id))
        service.unfollow(alice.id, bob.id)
        service.request(alice.id, bob.id)
        service.cancel(alice.id, bob.id)
        service.accept(bob.id, alice.id)
        assertEquals(FollowState.NONE, service.state(alice.id, bob.id))
    }

    @Test fun `requests require approval and either member can end the relationship`() {
        assertEquals(FollowState.NONE, service.state(alice.id, bob.id))
        service.request(alice.id, bob.id)
        service.request(alice.id, bob.id)
        assertEquals(FollowState.PENDING, service.state(alice.id, bob.id))
        assertEquals(listOf(alice.id), service.list(bob.id, FollowList.REQUESTS).members.map { it.id })
        assertEquals(1L, service.pendingCount(bob.id))
        assertTrue(service.list(alice.id, FollowList.FOLLOWING).members.isEmpty())
        service.accept(bob.id, alice.id)
        service.request(alice.id, bob.id)
        assertEquals(FollowState.ACCEPTED, service.state(alice.id, bob.id))
        assertEquals(listOf(bob.id), service.list(alice.id, FollowList.FOLLOWING).members.map { it.id })
        service.removeFollower(bob.id, alice.id)
        assertEquals(FollowState.NONE, service.state(alice.id, bob.id))
        service.request(alice.id, bob.id)
        service.reject(bob.id, alice.id)
        service.request(alice.id, bob.id)
        service.unfollow(alice.id, bob.id)
        service.accept(bob.id, alice.id)
        assertEquals(FollowState.NONE, service.state(alice.id, bob.id))
    }
}

private class MemoryFollows : Follows {
    override fun count(member: UserId, kind: FollowList) = list(member, kind, Int.MAX_VALUE, null).size.toLong()
    private val rows = mutableMapOf<Pair<UserId, UserId>, FollowState>()
    override fun state(follower: UserId, followed: UserId) = rows[follower to followed] ?: FollowState.NONE
    override fun request(follower: UserId, followed: UserId, now: Instant) { rows.putIfAbsent(follower to followed, FollowState.PENDING) }
    override fun accept(follower: UserId, followed: UserId, now: Instant) {
        if (state(follower, followed) == FollowState.PENDING) rows[follower to followed] = FollowState.ACCEPTED
    }
    override fun remove(follower: UserId, followed: UserId, only: FollowState?) {
        if (only == null || state(follower, followed) == only) rows.remove(follower to followed)
    }
    override fun list(member: UserId, kind: FollowList, limit: Int, after: UserId?): List<UserId> = rows.entries.filter {
        when (kind) {
            FollowList.REQUESTS -> it.key.second == member && it.value == FollowState.PENDING
            FollowList.FOLLOWERS -> it.key.second == member && it.value == FollowState.ACCEPTED
            FollowList.FOLLOWING -> it.key.first == member && it.value == FollowState.ACCEPTED
        }
    }.map { if (kind == FollowList.FOLLOWING) it.key.second else it.key.first }
        .sortedBy { it.value.toString() }.filter { after == null || it.value.toString() > after.value.toString() }.take(limit)
    override fun pendingCount(member: UserId) = rows.count { it.key.second == member && it.value == FollowState.PENDING }.toLong()
}
