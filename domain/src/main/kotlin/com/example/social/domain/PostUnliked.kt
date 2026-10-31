package com.example.social.domain

import com.example.social.sharedkernel.DomainEvent

data class PostUnliked(val post: PostId, val actor: UserId) : DomainEvent
