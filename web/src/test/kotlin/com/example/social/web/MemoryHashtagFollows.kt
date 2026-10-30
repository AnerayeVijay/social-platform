package com.example.social.web

import com.example.social.application.*
import com.example.social.domain.*

internal class MemoryHashtagFollows : HashtagFollows {
    private val rows = mutableSetOf<Pair<UserId, Hashtag>>()
    override fun add(member: UserId, tag: Hashtag) { rows.add(member to tag) }
    override fun remove(member: UserId, tag: Hashtag) { rows.remove(member to tag) }
    override fun list(member: UserId): List<Hashtag> = rows.filter { it.first == member }.map { it.second }.sortedBy { it.value }
}
