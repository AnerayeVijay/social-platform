package com.example.social.domain

import com.example.social.sharedkernel.DomainEvent

data class AccountRenamed(val account: UserId, val displayName: String) : DomainEvent
