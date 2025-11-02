package com.eduracha.services

import com.eduracha.models.*
import com.eduracha.repository.QuizRepository
import com.eduracha.repository.PreguntaRepository
import java.time.Instant
import kotlin.math.max

class QuizService(
    private val quizRepo: QuizRepository,
    private val preguntaRepo: PreguntaRepository
) {

    companion object {
        const val XP_POR_RESPUESTA_CORRECTA = 10
        const val UMBRAL_RAPIDEZ_SEG = 30
        const val BONIFICACION_RAPIDEZ = 10
        const val BONIFICACION_PRIMERA_VEZ = 5
        const val BONIFICACION_TODO_CORRECTO = 15
        const val VIDA_REGEN_MINUTOS = 30
        const val VIDAS_MAX = 5
    }

    // Iniciar quiz
    suspend fun iniciarQuiz(cursoId: String, temaId: String, userId: String): IniciarQuizResponse {
        // 1. Verificar inscripción
        val inscripcion = quizRepo.obtenerInscripcion(cursoId, userId)
            ?: throw IllegalStateException("No estás inscrito en este curso")

        if (inscripcion.estado != "aprobado" && inscripcion.estado != "en_progreso") {
            throw IllegalStateException("Estado de inscripción inválido")
        }

        // 2. Regenerar vidas si es necesario
        val inscripcionActualizada = quizRepo.regenerarVidas(cursoId, inscripcion, VIDA_REGEN_MINUTOS)
        if (inscripcionActualizada.vidasActuales != inscripcion.vidasActuales) {
            quizRepo.actualizarInscripcion(cursoId, inscripcionActualizada)
        }

        // 3. Verificar vidas
        if (inscripcionActualizada.vidasActuales <= 0) {
            throw IllegalStateException("No tienes vidas disponibles. Espera ${VIDA_REGEN_MINUTOS} minutos para recuperar una vida.")
        }

        // 4. Verificar que el tema pertenece al curso
        val temaExiste = quizRepo.verificarTemaEnCurso(cursoId, temaId)
        if (!temaExiste) {
            throw IllegalStateException("El tema no pertenece a este curso")
        }

        // 5. Verificar que vio la explicación
        val vioExplicacion = quizRepo.verificarExplicacionVista(userId, temaId)
        if (!vioExplicacion) {
            throw IllegalStateException("Debes ver la explicación antes de iniciar el quiz")
        }

        // 6. Obtener preguntas del tema y mezclarlas
        val preguntas = preguntaRepo.obtenerPreguntasPorCursoYEstado(cursoId, "aprobada")
            .filter { it.temaId == temaId }

        if (preguntas.isEmpty()) {
            throw IllegalStateException("No hay preguntas disponibles para este tema")
        }

        val preguntasMezcladas = preguntas.shuffled().take(10) // máximo 10 preguntas
        val quizPreguntas = preguntasMezcladas.mapIndexed { index, pregunta ->
            QuizPregunta(
                preguntaId = pregunta.id ?: "",
                orden = index + 1
            )
        }

        // 7. Crear quiz
        val quiz = Quiz(
            cursoId = cursoId,
            temaId = temaId,
            estudianteId = userId,
            preguntas = quizPreguntas,
            inicio = Instant.now().toString(),
            estado = "en_progreso",
            intentoNumero = inscripcionActualizada.intentosHechos + 1
        )

        val quizId = quizRepo.crearQuiz(quiz)

        // 8. Preparar respuesta (sin respuestas correctas)
        val preguntasResponse = preguntasMezcladas.mapIndexed { index, pregunta ->
            PreguntaQuizResponse(
                id = pregunta.id ?: "",
                orden = index + 1,
                texto = pregunta.texto,
                opciones = pregunta.opciones.map { OpcionResponse(it.id, it.texto) }
            )
        }

        return IniciarQuizResponse(
            quizId = quizId,
            preguntas = preguntasResponse
        )
    }

    // Finalizar quiz
    suspend fun finalizarQuiz(
        quizId: String,
        respuestas: List<RespuestaUsuario>,
        userId: String
    ): FinalizarQuizResponse {
        // 1. Obtener quiz
        val quiz = quizRepo.obtenerQuizPorId(quizId)
            ?: throw IllegalStateException("Quiz no encontrado")

        // 2. Verificar propiedad
        if (quiz.estudianteId != userId) {
            throw IllegalStateException("No tienes permiso para finalizar este quiz")
        }

        // 3. Verificar estado
        if (quiz.estado != "en_progreso") {
            throw IllegalStateException("Este quiz ya fue finalizado")
        }

        // 4. Evaluar respuestas
        val respuestasEvaluadas = evaluarRespuestas(quiz, respuestas)
        val correctas = respuestasEvaluadas.count { it.esCorrecta }
        val incorrectas = respuestasEvaluadas.size - correctas

        // 5. Calcular tiempo
        val tiempoTotal = respuestas.sumOf { it.tiempoSeg }
        val tiempoPromedio = if (respuestas.isNotEmpty()) tiempoTotal.toDouble() / respuestas.size else 0.0

        // 6. Calcular XP y bonificaciones
        val xpBase = correctas * XP_POR_RESPUESTA_CORRECTA
        val bonRapidez = if (tiempoPromedio < UMBRAL_RAPIDEZ_SEG) BONIFICACION_RAPIDEZ else 0
        val bonPerfect = if (incorrectas == 0 && correctas > 0) BONIFICACION_TODO_CORRECTO else 0

        val esPrimeraVez = quizRepo.esPrimeraVezAprobado(userId, quiz.cursoId, quiz.temaId)
        val porcentaje = if (respuestas.isNotEmpty()) (correctas * 100) / respuestas.size else 0
        val bonPrimera = if (esPrimeraVez && porcentaje >= 70) BONIFICACION_PRIMERA_VEZ else 0

        val xpTotal = xpBase + bonRapidez + bonPrimera + bonPerfect

        // 7. Actualizar vidas
        val inscripcion = quizRepo.obtenerInscripcion(quiz.cursoId, userId)
            ?: throw IllegalStateException("Inscripción no encontrada")

        val nuevasVidas = max(0, inscripcion.vidasActuales - incorrectas)
        val inscripcionActualizada = inscripcion.copy(
            vidasActuales = nuevasVidas,
            intentosHechos = inscripcion.intentosHechos + 1
        )
        quizRepo.actualizarInscripcion(quiz.cursoId, inscripcionActualizada)

        // 8. Actualizar quiz
        val quizFinalizado = quiz.copy(
            fin = Instant.now().toString(),
            tiempoUsadoSeg = tiempoTotal,
            tiempoPromedioPorPregunta = tiempoPromedio,
            preguntasCorrectas = correctas,
            preguntasIncorrectas = incorrectas,
            experienciaGanada = xpTotal,
            bonificacionRapidez = bonRapidez,
            bonificacionPrimeraVez = bonPrimera,
            bonificacionTodoCorrecto = bonPerfect,
            estado = "finalizado",
            respuestas = respuestasEvaluadas
        )
        quizRepo.actualizarQuiz(quizFinalizado)

        // 9. Actualizar progreso
        if (porcentaje >= 70) {
            ServicioProgreso.actualizarProgreso(userId, "quiz", true)
        }

        return FinalizarQuizResponse(
            preguntasCorrectas = correctas,
            preguntasIncorrectas = incorrectas,
            experienciaGanada = xpTotal,
            vidasRestantes = nuevasVidas,
            bonificaciones = BonificacionesResponse(
                rapidez = bonRapidez,
                primeraVez = bonPrimera,
                todoCorrecto = bonPerfect
            )
        )
    }

    // Evaluar respuestas
    private suspend fun evaluarRespuestas(
        quiz: Quiz,
        respuestasUsuario: List<RespuestaUsuario>
    ): List<RespuestaQuiz> {
        return respuestasUsuario.map { respuesta ->
            val pregunta = preguntaRepo.obtenerPreguntaPorId(respuesta.preguntaId)
                ?: throw IllegalStateException("Pregunta no encontrada: ${respuesta.preguntaId}")

            val opcionCorrecta = pregunta.opciones.indexOfFirst { it.esCorrecta }
            val esCorrecta = respuesta.respuestaSeleccionada == opcionCorrecta

            RespuestaQuiz(
                preguntaId = respuesta.preguntaId,
                respuestaSeleccionada = respuesta.respuestaSeleccionada,
                tiempoSeg = respuesta.tiempoSeg,
                esCorrecta = esCorrecta
            )
        }
    }

    // Obtener revisión del quiz
    suspend fun obtenerRevision(quizId: String, userId: String): RevisionQuizResponse {
        val quiz = quizRepo.obtenerQuizPorId(quizId)
            ?: throw IllegalStateException("Quiz no encontrado")

        if (quiz.estudianteId != userId) {
            throw IllegalStateException("No tienes permiso para ver este quiz")
        }

        if (quiz.estado != "finalizado") {
            throw IllegalStateException("El quiz no está finalizado")
        }

        val preguntasRevision = quiz.respuestas.map { respuesta ->
            val pregunta = preguntaRepo.obtenerPreguntaPorId(respuesta.preguntaId)
                ?: throw IllegalStateException("Pregunta no encontrada")

            val respuestaCorrecta = pregunta.opciones.indexOfFirst { it.esCorrecta }

            PreguntaRevisionResponse(
                preguntaId = pregunta.id ?: "",
                texto = pregunta.texto,
                opciones = pregunta.opciones,
                respuestaUsuario = respuesta.respuestaSeleccionada,
                respuestaCorrecta = respuestaCorrecta,
                explicacion = pregunta.explicacionCorrecta ?: "No hay explicación disponible"
            )
        }

        return RevisionQuizResponse(
            quizId = quizId,
            preguntas = preguntasRevision
        )
    }

    // Marcar explicación vista
    suspend fun marcarExplicacionVista(userId: String, temaId: String) {
        quizRepo.marcarExplicacionVista(userId, temaId)
    }

    // Obtener vidas del estudiante
    suspend fun obtenerVidas(cursoId: String, userId: String): Map<String, Any> {
        val inscripcion = quizRepo.obtenerInscripcion(cursoId, userId)
            ?: throw IllegalStateException("No estás inscrito en este curso")

        val inscripcionActualizada = quizRepo.regenerarVidas(cursoId, inscripcion, VIDA_REGEN_MINUTOS)

        if (inscripcionActualizada.vidasActuales != inscripcion.vidasActuales) {
            quizRepo.actualizarInscripcion(cursoId, inscripcionActualizada)
        }

        val ahora = System.currentTimeMillis()
        val minutosParaProximaVida = if (inscripcionActualizada.vidasActuales < inscripcionActualizada.vidasMax) {
            VIDA_REGEN_MINUTOS - ((ahora - inscripcionActualizada.ultimaRegen) / (1000 * 60)).toInt()
        } else {
            0
        }

        return mapOf(
            "vidasActuales" to inscripcionActualizada.vidasActuales,
            "vidasMax" to inscripcionActualizada.vidasMax,
            "minutosParaProximaVida" to minutosParaProximaVida
        )
    }
}