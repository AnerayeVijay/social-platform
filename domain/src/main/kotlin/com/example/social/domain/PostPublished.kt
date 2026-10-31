package com.example.social.domain

import com.example.social.sharedkernel.DomainEvent

data class PostPublished(val post: PostId) : DomainEvent
