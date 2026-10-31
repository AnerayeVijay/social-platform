package com.example.social.domain

import com.example.social.sharedkernel.DomainEvent

data class CommentAdded(val post: PostId, val comment: CommentId) : DomainEvent
