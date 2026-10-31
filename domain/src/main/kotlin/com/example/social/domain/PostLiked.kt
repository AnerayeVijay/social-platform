package com.example.social.domain

import com.example.social.sharedkernel.DomainEvent

data class PostLiked(val post: PostId, val actor: UserId) : DomainEvent
