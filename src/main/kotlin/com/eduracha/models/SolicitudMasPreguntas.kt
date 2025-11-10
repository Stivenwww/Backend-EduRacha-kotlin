package com.eduracha.models

import kotlinx.serialization.Serializable

@Serializable
data class SolicitudMasPreguntas(
    val id: String? = null,
    val cursoId: String = "",
    val temaId: String = "",
    val tituloTema: String? = null,
    val estudianteId: String = "",
    val estudianteNombre: String = "",
    val estudianteEmail: String? = null,
    val preguntasActuales: Int = 0,
    val preguntasVistas: Int = 0,
    val estado: String = "pendiente", // pendiente, atendida, rechazada
    val fechaSolicitud: Long = System.currentTimeMillis(),
    val fechaAtencion: Long? = null,
    val mensaje: String = "",
    val prioridad: String = "normal" // normal, alta, urgente
)

@Serializable
data class ResumenSolicitudesTema(
    val cursoId: String,
    val temaId: String,
    val tituloTema: String,
    val estudiantesAfectados: Int,
    val preguntasActuales: Int,
    val fechaPrimeraSolicitud: Long
)