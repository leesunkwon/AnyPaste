package com.kotlinsun.anypaste.model

import com.google.firebase.Timestamp

data class UserProfile(
    val id: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String = "",
    val createdAt: Timestamp? = null,
    val lastActiveAt: Timestamp? = null,
)
