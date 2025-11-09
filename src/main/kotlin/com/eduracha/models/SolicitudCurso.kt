package com.eduracha.models

import kotlinx.serialization.Serializable

@Serializable
data class SolicitudCurso(
    val id: String? = null,
    val cursoId: String = "",
    val codigoCurso: String = "",
    val estudianteId: String = "",
    val estudianteNombre: String = "",
    val estudianteEmail: String = "",
    val estado: EstadoSolicitud = EstadoSolicitud.PENDIENTE,
    val fechaSolicitud: String = "",
    val fechaRespuesta: String? = null,
    val mensaje: String? = null
)

@Serializable
enum class EstadoSolicitud {
    PENDIENTE,
    ACEPTADA,
    RECHAZADA
}

@Serializable
data class SolicitudRequest(
    val codigoCurso: String,
    val estudianteId: String,
    val estudianteNombre: String,
    val estudianteEmail: String,
    val mensaje: String? = null
)

@Serializable
data class RespuestaSolicitudRequest(
    val aceptar: Boolean,
    val mensaje: String? = null
)

