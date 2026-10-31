package com.example.social.domain

import com.example.social.sharedkernel.DomainEvent

data class FollowRemoved(val follower: UserId, val followed: UserId) : DomainEvent
