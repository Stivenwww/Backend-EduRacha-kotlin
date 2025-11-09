package com.eduracha.models

import kotlinx.serialization.Serializable

@Serializable
data class Usuario(
    val uid: String = "",
    val nombreCompleto: String = "",
    val correo: String = "",
    val rol: String = "estudiante",
    val fechaRegistro: Long = 0,
    val apodo: String = "",
    val perfil: PerfilUsuario = PerfilUsuario() 
    
)

@Serializable
data class PerfilUsuario(
    val cursos: Map<String, PerfilCurso> = emptyMap() // cursoId -> PerfilCurso
)


@Serializable
data class PerfilCurso(
    val cursoId: String = "",
    val estado: String = "aprobado",
    val vidas: Vidas = Vidas(),
    val racha: Racha = Racha(),
    val progreso: Progreso = Progreso(),
    val experiencia: Int = 0,
    val temasCompletados: Map<String, EstadoTema> = emptyMap(),
    val proximoQuizDisponible: Long = 0
)

@Serializable
data class EstadoTema(
    val temaId: String = "",
    val quizzesRealizados: Int = 0,
    val quizzesRequeridos: Int = 0,
    val porcentajePromedio: Int = 0,
    val aprobado: Boolean = false,
    val fechaPrimerIntento: Long = 0,
    val fechaUltimoIntento: Long = 0,
    val preguntasVistas: Set<String> = emptySet()
)

// Y también necesitas estos modelos básicos:
@Serializable
data class Vidas(
    val actuales: Int = 5,
    val max: Int = 5,
    val ultimaRegen: Long = 0
)

@Serializable
data class Racha(
    val diasConsecutivos: Int = 0,
    val ultimaFecha: Long = 0
)

@Serializable
data class Progreso(
    val porcentaje: Int = 0,
    val temasCompletados: Int = 0,
    val quizzesCompletados: Int = 0,
    val practicasCompletadas: Int = 0,
    val puntosTotales: Int = 0,
    val ultimaActividad: Long = 0
)
