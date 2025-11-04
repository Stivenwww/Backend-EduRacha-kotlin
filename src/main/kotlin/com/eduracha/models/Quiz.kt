package com.eduracha.models

import kotlinx.serialization.Serializable

@Serializable
data class Quiz(
    val id: String? = null,
    val cursoId: String = "",
    val temaId: String = "",
    val estudianteId: String = "",
    val preguntas: List<QuizPregunta> = emptyList(),
    val inicio: String = "",
    val fin: String? = null,
    val tiempoUsadoSeg: Int = 0,
    val estado: String = "en_progreso", // "en_progreso", "finalizado", "abandonado"
    val intentoNumero: Int = 1,
    val preguntasCorrectas: Int = 0,
    val preguntasIncorrectas: Int = 0,
    val tiempoPromedioPorPregunta: Double = 0.0,
    val recursoVisto: Boolean = false,
    val experienciaGanada: Int = 0,
    val bonificacionRapidez: Int = 0,
    val bonificacionPrimeraVez: Int = 0,
    val bonificacionTodoCorrecto: Int = 0,
    val respuestas: List<RespuestaQuiz> = emptyList()
)

@Serializable
data class QuizPregunta(
    val preguntaId: String = "",
    val orden: Int = 0
)

@Serializable
data class RespuestaQuiz(
    val preguntaId: String = "",
    val respuestaSeleccionada: Int = -1, // índice de la opción seleccionada
    val tiempoSeg: Int = 0,
    val esCorrecta: Boolean = false
)

// Requests
@Serializable
data class IniciarQuizRequest(
    val cursoId: String,
    val temaId: String
)

@Serializable
data class FinalizarQuizRequest(
    val quizId: String,
    val respuestas: List<RespuestaUsuario>
)

@Serializable
data class RespuestaUsuario(
    val preguntaId: String,
    val respuestaSeleccionada: Int,
    val tiempoSeg: Int
)

// Responses
@Serializable
data class IniciarQuizResponse(
    val quizId: String,
    val preguntas: List<PreguntaQuizResponse>
)

@Serializable
data class PreguntaQuizResponse(
    val id: String,
    val orden: Int,
    val texto: String,
    val opciones: List<OpcionResponse>
)

@Serializable
data class OpcionResponse(
    val id: Int,
    val texto: String
    // NO incluir esCorrecta aquí
)

@Serializable
data class FinalizarQuizResponse(
    val preguntasCorrectas: Int,
    val preguntasIncorrectas: Int,
    val experienciaGanada: Int,
    val vidasRestantes: Int,
    val bonificaciones: BonificacionesResponse
)

@Serializable
data class BonificacionesResponse(
    val rapidez: Int,
    val primeraVez: Int,
    val todoCorrecto: Int
)

@Serializable
data class RevisionQuizResponse(
    val quizId: String,
    val preguntas: List<PreguntaRevisionResponse>
)

@Serializable
data class PreguntaRevisionResponse(
    val preguntaId: String,
    val texto: String,
    val opciones: List<Opcion>,
    val respuestaUsuario: Int,
    val respuestaCorrecta: Int,
    val explicacion: String
)

// Modelo de inscripción (si no lo tienes)
@Serializable
data class Inscripcion(
    val userId: String = "",
    val cursoId: String = "",
    val estado: String = "en_progreso", // "aprobado", "en_progreso", "inactivo"
    val vidasActuales: Int = 5,
    val vidasMax: Int = 5,
    val ultimaRegen: Long = 0,
    val intentosHechos: Int = 0
)
@Serializable
data class TemaInfoResponse(
    val temaId: String,
    val cursoId: String,
    val preguntasDisponibles: Int,
    val vidasActuales: Int,
    val explicacionVista: Boolean,
    val inscrito: Boolean
)


data class RetroalimentacionFallosResponse(
    val quizId: String,
    val totalFallos: Int,
    val preguntasFalladas: List<RetroalimentacionPregunta>
)

data class RetroalimentacionPregunta(
    val preguntaId: String,
    val texto: String,
    val respuestaUsuarioTexto: String,
    val respuestaCorrectaTexto: String,
    val explicacion: String
)
