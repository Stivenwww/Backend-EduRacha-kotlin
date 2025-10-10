package com.eduracha.models

@kotlinx.serialization.Serializable
data class User(
    val email: String,
    val password: String
)
