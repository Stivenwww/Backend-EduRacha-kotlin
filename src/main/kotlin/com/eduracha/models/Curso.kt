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
    val explicacionEstado: String? = null, // "pendiente", "aprobada", "rechazada"
    val programacion: ProgramacionTema? = null
)

@Serializable
data class Curso(
    val id: String? = null,
    val titulo: String = "",
    val codigo: String = "",
    val descripcion: String = "",
    val docenteId: String = "",
    val duracionDias: Int = 0,
    val fechaInicio: Long = 0,
    val fechaFin: Long = 0,
    val temas: Map<String, Tema>? = null,
    val programacion: ProgramacionCurso? = null,
    val estado: String = "",
    val fechaCreacion: String = ""
)

@Serializable
data class ProgramacionCurso(
    val temasOrdenados: List<String> = emptyList(), // IDs de temas en orden
    val distribucionTemporal: Map<String, RangoTema> = emptyMap() // temaId -> fechas
)

@Serializable
data class RangoTema(
    val temaId: String = "",
    val titulo: String = "",
    val fechaInicio: Long = 0,
    val fechaFin: Long = 0,
    val quizzesRequeridos: Int = 0, // Calculado automáticamente
    val diasAsignados: Int = 0
)

@Serializable
data class GenerarExplicacionRequest(
    val cursoId: String,
    val temaId: String,
    val tituloTema: String,
    val contenidoTema: String? = null
)

@Serializable
data class ActualizarExplicacionRequest(
    val explicacion: String,
    val fuente: String = "docente"
)

@Serializable
data class ExplicacionResponse(
    val message: String,
    val explicacion: String,
    val fuente: String,
    val tema: Tema
)

@Serializable
data class ProgramacionTema(
    val objetivos: String? = null,
    val conceptosClave: String? = null,
    val ejemplos: String? = null,
    val pseudocodigo: String? = null,
    val codigo: String? = null
)
