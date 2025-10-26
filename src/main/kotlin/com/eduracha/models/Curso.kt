package com.eduracha.models

import kotlinx.serialization.Serializable

@Serializable
data class Tema(
    val id: String? = null,
    val titulo: String = "",
    val contenido: String = "",
    val archivoUrl: String = "",
    val tipo: String = "",
    val fechaCreacion: String = "",
    val explicacion: String? = null,
    val explicacionFuente: String? = null, // "ia" o "docente"
    val explicacionUltimaActualizacion: String? = null,
    val explicacionEstado: String? = null // "pendiente", "aprobada", "rechazada"
)

@Serializable
data class Curso(
    val id: String? = null,
    val titulo: String = "",
    val codigo: String = "",
    val descripcion: String = "",
    val docenteId: String = "",
    val duracionDias: Int = 0,
    val temas: Map<String, Tema>? = null,
    val estado: String = "",
    val fechaCreacion: String = "",

)

// Request para generar explicación con IA
@Serializable
data class GenerarExplicacionRequest(
    val cursoId: String,
    val temaId: String,
    val tituloTema: String,
    val contenidoTema: String? = null
)

// Request para actualizar explicación manualmente
@Serializable
data class ActualizarExplicacionRequest(
    val explicacion: String,
    val fuente: String = "docente"
)

// Response para explicación generada
@Serializable
data class ExplicacionResponse(
    val message: String,
    val explicacion: String,
    val fuente: String,
    val tema: Tema
)
