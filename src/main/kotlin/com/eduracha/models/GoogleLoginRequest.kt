package com.eduracha.models

import kotlinx.serialization.Serializable

@Serializable
data class GoogleLoginRequest(
    val googleToken: String
)
