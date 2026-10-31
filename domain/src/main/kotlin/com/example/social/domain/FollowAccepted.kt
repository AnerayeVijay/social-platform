package com.example.social.domain

import com.example.social.sharedkernel.DomainEvent
import java.time.Instant

data class FollowAccepted(val follower: UserId, val followed: UserId, val at: Instant) : DomainEvent
