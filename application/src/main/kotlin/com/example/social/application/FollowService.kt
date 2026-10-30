package com.example.social.application

import com.example.social.domain.UserId
import java.time.Instant

enum class FollowState { NONE, PENDING, ACCEPTED }
enum class FollowList { REQUESTS, FOLLOWERS, FOLLOWING }
data class FollowMember(val id: UserId, val displayName: String)
data class FollowPage(val members: List<FollowMember>, val next: UserId?)

interface Follows {
    fun state(follower: UserId, followed: UserId): FollowState
    fun request(follower: UserId, followed: UserId, now: Instant)
    fun accept(follower: UserId, followed: UserId, now: Instant)
    fun remove(follower: UserId, followed: UserId, only: FollowState? = null)
    fun list(member: UserId, kind: FollowList, limit: Int, after: UserId?): List<UserId>
    fun pendingCount(member: UserId): Long
    fun count(member: UserId, kind: FollowList): Long
    fun suggestions(member: UserId, limit: Int): List<UserId> = emptyList()
}

class FollowService(private val accounts: Accounts, private val follows: Follows, private val now: () -> Instant = Instant::now) {
    fun search(actor: UserId, name: String, limit: Int = 20, after: UserId? = null): FollowPage {
        verified(actor)
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val query = name.trim()
        require(query.length <= 80) { "Search name must contain at most 80 characters" }
        if (query.isEmpty()) return FollowPage(emptyList(), null)
        val members = accounts.searchMembers(query, actor, limit + 1, after)
        return FollowPage(members.take(limit), if (members.size > limit) members[limit - 1].id else null)
    }

    fun state(follower: UserId, followed: UserId) = follows.state(follower, followed)
    fun request(actor: UserId, target: UserId) {
        require(actor != target) { "You cannot follow yourself" }
        verified(actor)
        verified(target)
        follows.request(actor, target, now())
    }
    fun accept(actor: UserId, follower: UserId) {
        verified(actor)
        follows.accept(follower, actor, now())
    }
    fun reject(actor: UserId, follower: UserId) {
        verified(actor)
        follows.remove(follower, actor, FollowState.PENDING)
    }
    fun unfollow(actor: UserId, target: UserId) {
        verified(actor)
        follows.remove(actor, target)
    }
    fun cancel(actor: UserId, target: UserId) {
        verified(actor)
        follows.remove(actor, target, FollowState.PENDING)
    }
    fun removeFollower(actor: UserId, follower: UserId) {
        verified(actor)
        follows.remove(follower, actor, FollowState.ACCEPTED)
    }
    fun pendingCount(actor: UserId): Long { verified(actor); return follows.pendingCount(actor) }
    fun count(actor: UserId, kind: FollowList): Long { verified(actor); return follows.count(actor, kind) }
    fun list(actor: UserId, kind: FollowList, limit: Int = 20, after: UserId? = null): FollowPage {
        verified(actor)
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val ids = follows.list(actor, kind, limit + 1, after)
        val members = ids.take(limit).mapNotNull { id -> accounts.byId(id)?.let { FollowMember(id, it.displayName) } }
        return FollowPage(members, if (ids.size > limit) ids[limit - 1] else null)
    }
    fun suggestions(actor: UserId, limit: Int = 3): List<FollowMember> {
        verified(actor)
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return follows.suggestions(actor, limit).mapNotNull { id -> accounts.byId(id)?.let { FollowMember(id, it.displayName) } }
    }
    private fun verified(id: UserId) {
        require(accounts.byId(id)?.verified == true) { "A verified member account is required" }
    }
}
