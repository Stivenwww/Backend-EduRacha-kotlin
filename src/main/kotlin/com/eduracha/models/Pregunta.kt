package com.eduracha.models

import kotlinx.serialization.Serializable

@Serializable
data class Pregunta(
    val id: String? = null,
    val cursoId: String = "",
    val temaId: String = "",
    val texto: String = "",
    val opciones: List<Opcion> = emptyList(),
    val fuente: String = "", // "ia", "docente" o "importada"
    val estado: String = "", //  "pendiente_revision", etc.
    val dificultad: String? = null, // "facil", "medio", "dificil"
    val creadoPor: String = "",
    val fechaCreacion: String = "",
    val metadatosIA: MetadatosIA? = null,
    val revisadoPor: String? = null,
    val fechaRevision: String? = null,
    val notasRevision: String? = null,
    val modificada: Boolean = false,
    val explicacionCorrecta: String? = null // Texto explicativo de por qué la respuesta es la correcta

)

@Serializable
data class Opcion(
    val id: Int = 0,
    val texto: String = "",
    val esCorrecta: Boolean = false
)

@Serializable
data class MetadatosIA(
    val generadoPor: String? = null, // "openai", "profesor",$etc.
    val instruccion: String? = null,
    val loteId: String? = null,
    val versionOriginal: VersionOriginal? = null
)

@Serializable
data class VersionOriginal(
    val texto: String? = null,
    val opciones: List<String>? = null
)
