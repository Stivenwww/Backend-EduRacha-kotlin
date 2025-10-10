package com.eduracha.models

import kotlinx.serialization.Serializable

@Serializable
data class RegistroRequest(
    val nombreCompleto: String,
    val apodo: String,
    val correo: String,
    val contrasena: String,
    val rol: String // "estudiante" o "docente"
)
