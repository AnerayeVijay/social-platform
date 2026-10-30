package com.example.social.application

import com.example.social.domain.Hashtag
import com.example.social.domain.UserId

interface HashtagFollows {
    fun add(member: UserId, tag: Hashtag)
    fun remove(member: UserId, tag: Hashtag)
    fun list(member: UserId): List<Hashtag>
}

class HashtagFollowService(private val follows: HashtagFollows) {
    fun follow(member: UserId, tag: Hashtag) = follows.add(member, tag)
    fun unfollow(member: UserId, tag: Hashtag) = follows.remove(member, tag)
    fun followed(member: UserId): List<Hashtag> = follows.list(member)
}
