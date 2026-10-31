package com.example.social.domain

import com.example.social.sharedkernel.DomainEvent

data class PostDeleted(val post: PostId) : DomainEvent
