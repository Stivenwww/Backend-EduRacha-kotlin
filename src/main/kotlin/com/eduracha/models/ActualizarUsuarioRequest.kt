package com.eduracha.models

import kotlinx.serialization.Serializable

@Serializable
data class ActualizarUsuarioRequest(
    val nombreCompleto: String? = null,
    val apodo: String? = null,
    val correo: String? = null,
    val contrasena: String? = null,
    val rol: String? = null // opcional: "estudiante", "docente", "admin", ...
)
