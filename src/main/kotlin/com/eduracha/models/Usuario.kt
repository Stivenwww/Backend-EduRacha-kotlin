package com.eduracha.models

import kotlinx.serialization.Serializable

@Serializable
data class Usuario(
    val uid: String = "",
    val nombreCompleto: String = "",
    val apodo: String = "",
    val correo: String = "",
    val rol: String = "",
    val fechaRegistro: Long = 0
)
