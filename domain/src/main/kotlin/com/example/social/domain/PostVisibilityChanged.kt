package com.example.social.domain

import com.example.social.sharedkernel.DomainEvent

data class PostVisibilityChanged(val post: PostId, val visibility: PostVisibility) : DomainEvent
