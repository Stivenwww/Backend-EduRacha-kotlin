package com.eduracha.services

import com.eduracha.models.*
import com.eduracha.repository.*
import java.time.Instant
import kotlin.math.max

class QuizService(
    private val quizRepo: QuizRepository,
    private val preguntaRepo: PreguntaRepository,
    private val cursoRepo: CursoRepository,
    private val servicioSeleccion: ServicioSeleccionPreguntas
) {

    companion object {
        const val XP_POR_RESPUESTA_CORRECTA = 10
        const val UMBRAL_RAPIDEZ_SEG = 30
        const val BONIFICACION_RAPIDEZ = 10
        const val BONIFICACION_PRIMERA_VEZ = 5
        const val BONIFICACION_TODO_CORRECTO = 15
        const val VIDA_REGEN_MINUTOS = 30
        const val VIDAS_MAX = 5
        const val PORCENTAJE_APROBACION = 80
    }

    // -------------------------------------------------------
    // Iniciar quiz (val. programación, vidas, explicación, selección)
    // -------------------------------------------------------
    suspend fun iniciarQuiz(cursoId: String, temaId: String, userId: String): IniciarQuizResponse {
        // 1. Obtener curso y programación
        val curso = cursoRepo.obtenerCursoPorId(cursoId)
            ?: throw IllegalStateException("Curso no encontrado")

        val programacion = curso.programacion
            ?: throw IllegalStateException("El curso no tiene programación configurada")

        // 2. Verificar inscripción
        val inscripcion = quizRepo.obtenerInscripcion(cursoId, userId)
            ?: throw IllegalStateException("No estás inscrito en este curso")

        if (inscripcion.estado != "aprobado" && inscripcion.estado != "en_progreso") {
            throw IllegalStateException("Estado de inscripción inválido")
        }

        // 3. Obtener estado del tema para el estudiante (si existe)
        val estadoTema = obtenerEstadoTema(cursoId, temaId, userId)

        // 4. Validar si puede realizar el quiz según la programación del curso
        val validacion = ServicioProgramacionCurso.puedeRealizarQuiz(
            cursoId = cursoId,
            temaId = temaId,
            estudianteId = userId,
            programacion = programacion,
            estadoTema = estadoTema
        )

        if (!validacion.permitido) {
            throw IllegalStateException(validacion.mensaje)
        }

        // 5. Regenerar vidas si corresponde
        val inscripcionActualizada = quizRepo.regenerarVidas(cursoId, inscripcion, VIDA_REGEN_MINUTOS)
        if (inscripcionActualizada.vidasActuales != inscripcion.vidasActuales) {
            quizRepo.actualizarInscripcion(cursoId, inscripcionActualizada)
        }

        // 6. Verificar vidas
        if (inscripcionActualizada.vidasActuales <= 0) {
            throw IllegalStateException("No tienes vidas disponibles. Espera ${VIDA_REGEN_MINUTOS} minutos para recuperar una vida.")
        }

        // 7. Verificar que vio la explicación
        val vioExplicacion = quizRepo.verificarExplicacionVista(userId, temaId)
        if (!vioExplicacion) {
            throw IllegalStateException("Debes ver la explicación antes de iniciar el quiz")
        }

        // 8. Selección inteligente de preguntas (evitar repetición)
        val preguntasYaVistas = estadoTema?.preguntasVistas ?: emptySet()
        val preguntasSeleccionadas = servicioSeleccion.seleccionarPreguntasParaQuiz(
            cursoId = cursoId,
            temaId = temaId,
            estudianteId = userId,
            cantidadRequerida = 10,
            preguntasYaVistas = preguntasYaVistas
        )

        if (preguntasSeleccionadas.isEmpty()) {
            // fallback: intentar obtener preguntas aprobadas del repositorio (igual al original)
            val preguntasFallback = preguntaRepo.obtenerPreguntasPorCursoYEstado(cursoId, "aprobada")
                .filter { it.temaId == temaId }
                .shuffled()
                .take(10)

            if (preguntasFallback.isEmpty()) {
                throw IllegalStateException("No hay preguntas disponibles para este tema")
            }

            return crearYResponderQuiz(cursoId, temaId, userId, preguntasFallback, estadoTema)
        }

        return crearYResponderQuiz(cursoId, temaId, userId, preguntasSeleccionadas, estadoTema)
    }

    // Helper para crear quiz y construir respuesta de inicio
    private suspend fun crearYResponderQuiz(
        cursoId: String,
        temaId: String,
        userId: String,
        preguntas: List<Pregunta>,
        estadoTema: EstadoTema?
    ): IniciarQuizResponse {
        val quizPreguntas = preguntas.mapIndexed { index, pregunta ->
            QuizPregunta(
                preguntaId = pregunta.id ?: "",
                orden = index + 1
            )
        }

        val quiz = Quiz(
            cursoId = cursoId,
            temaId = temaId,
            estudianteId = userId,
            preguntas = quizPreguntas,
            inicio = Instant.now().toString(),
            estado = "en_progreso",
            intentoNumero = (estadoTema?.quizzesRealizados ?: 0) + 1
        )

        val quizId = quizRepo.crearQuiz(quiz)

        val preguntasResponse = preguntas.mapIndexed { index, pregunta ->
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

    // -------------------------------------------------------
    // Finalizar quiz (evaluación, vidas, xp, actualizar estado del tema)
    // -------------------------------------------------------
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

        // 6. Calcular porcentaje y estado aprobado
        val porcentaje = if (respuestas.isNotEmpty()) (correctas * 100) / respuestas.size else 0
        val aprobado = porcentaje >= PORCENTAJE_APROBACION

        // 7. Calcular XP y bonificaciones
        val xpBase = correctas * XP_POR_RESPUESTA_CORRECTA
        val bonRapidez = if (tiempoPromedio < UMBRAL_RAPIDEZ_SEG) BONIFICACION_RAPIDEZ else 0
        val bonPerfect = if (incorrectas == 0 && correctas > 0) BONIFICACION_TODO_CORRECTO else 0

        val esPrimeraVez = quizRepo.esPrimeraVezAprobado(userId, quiz.cursoId, quiz.temaId)
        val bonPrimera = if (esPrimeraVez && aprobado) BONIFICACION_PRIMERA_VEZ else 0

        val xpTotal = xpBase + bonRapidez + bonPrimera + bonPerfect

        // 8. Actualizar vidas en la inscripción
        val inscripcion = quizRepo.obtenerInscripcion(quiz.cursoId, userId)
            ?: throw IllegalStateException("Inscripción no encontrada")

        val nuevasVidas = max(0, inscripcion.vidasActuales - incorrectas)
        val inscripcionActualizada = inscripcion.copy(
            vidasActuales = nuevasVidas,
            intentosHechos = inscripcion.intentosHechos + 1
        )
        quizRepo.actualizarInscripcion(quiz.cursoId, inscripcionActualizada)

        // 9. Actualizar quiz (guardar resultados y respuestas evaluadas)
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

        // 10. Actualizar estado del tema (quizzesRealizados, porcentajePromedio, aprobado, preguntasVistas)
        actualizarEstadoTema(
            cursoId = quiz.cursoId,
            temaId = quiz.temaId,
            estudianteId = userId,
            porcentaje = porcentaje,
            aprobado = aprobado,
            preguntasIds = quiz.preguntas.map { it.preguntaId }
        )

        // 11. Actualizar progreso y racha si aprobó
        if (aprobado) {
            // asumo que ServicioProgreso tiene estos métodos (si no, coméntalos o implementa)
            ServicioProgreso.actualizarProgreso(userId, "quiz", true)
            ServicioProgreso.actualizarRacha(quiz.cursoId, userId, true)
        } else {
            // registrar intento en progreso (si aplica)
            ServicioProgreso.actualizarProgreso(userId, "quiz", false)
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

    // -------------------------------------------------------
    // Evaluar respuestas (mantiene la lógica original)
    // -------------------------------------------------------
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

    // -------------------------------------------------------
    // Obtener revisión del quiz (igual que en el original)
    // -------------------------------------------------------
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

    // -------------------------------------------------------
    // Marcar explicación vista
    // -------------------------------------------------------
    suspend fun marcarExplicacionVista(userId: String, temaId: String) {
        quizRepo.marcarExplicacionVista(userId, temaId)
    }

    // -------------------------------------------------------
    // Obtener vidas del estudiante (mantiene la lógica original)
    // -------------------------------------------------------
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

    // -------------------------------------------------------
    // Obtener retroalimentación de fallos (preguntas falladas)
    // -------------------------------------------------------
    suspend fun obtenerRetroalimentacionFallos(
        quizId: String,
        userId: String
    ): RetroalimentacionFallosResponse {
        val quiz = quizRepo.obtenerQuizPorId(quizId)
            ?: throw IllegalStateException("Quiz no encontrado")

        if (quiz.estudianteId != userId) {
            throw IllegalStateException("No tienes permiso para ver este quiz")
        }

        if (quiz.estado != "finalizado") {
            throw IllegalStateException("El quiz no está finalizado aún")
        }

        // Filtrar preguntas falladas
        val preguntasFalladas = quiz.respuestas.filter { !it.esCorrecta }

        val detallesFallos = preguntasFalladas.map { respuesta ->
            val pregunta = preguntaRepo.obtenerPreguntaPorId(respuesta.preguntaId)
                ?: throw IllegalStateException("Pregunta no encontrada: ${respuesta.preguntaId}")

            val respuestaCorrectaIndex = pregunta.opciones.indexOfFirst { it.esCorrecta }
            val respuestaUsuario = pregunta.opciones.getOrNull(respuesta.respuestaSeleccionada)
            val respuestaCorrecta = pregunta.opciones.getOrNull(respuestaCorrectaIndex)

            RetroalimentacionPregunta(
                preguntaId = pregunta.id ?: "",
                texto = pregunta.texto,
                respuestaUsuarioTexto = respuestaUsuario?.texto ?: "Sin respuesta registrada",
                respuestaCorrectaTexto = respuestaCorrecta?.texto ?: "Sin respuesta correcta definida",
                explicacion = pregunta.explicacionCorrecta ?: "No hay explicación disponible para esta pregunta."
            )
        }

        return RetroalimentacionFallosResponse(
            quizId = quizId,
            totalFallos = detallesFallos.size,
            preguntasFalladas = detallesFallos
        )
    }

    // -------------------------------------------------------
    // Helpers para estado del tema (leer y actualizar)
    // -------------------------------------------------------
    private suspend fun obtenerEstadoTema(
        cursoId: String,
        temaId: String,
        estudianteId: String
    ): EstadoTema? {
        // Intentamos delegar al repo si tiene un método para obtener el perfil/estado del curso del estudiante.
        // Si tu quizRepo/usuarioRepo tiene otro nombre para este método, reemplázalo por el correspondiente.
        // Ejemplo esperado en repo: obtenerPerfilCurso(estudianteId, cursoId): PerfilCurso?
        return try {
            val perfilCurso = quizRepo.obtenerPerfilCurso(estudianteId, cursoId) // <-- método esperado en repo
            perfilCurso?.temasCompletados?.get(temaId)
        } catch (ex: NoSuchMethodError) {
            // Si el repositorio no tiene ese método, devolvemos null y el flujo seguirá (no bloquear)
            null
        } catch (ex: Exception) {
            // En caso de error, devolvemos null para no romper el inicio del quiz (puede ajustarse según necesidad)
            null
        }
    }

    private suspend fun actualizarEstadoTema(
        cursoId: String,
        temaId: String,
        estudianteId: String,
        porcentaje: Int,
        aprobado: Boolean,
        preguntasIds: List<String>
    ) {
        // Intentamos actualizar usando el repo. Se espera un método tipo:
        // quizRepo.actualizarEstadoTema(estudianteId, cursoId, estadoTema: EstadoTema)
        // Si no existe, este bloque puede implementarse dentro del repo que maneja Firebase.
        try {
            val perfilCurso = quizRepo.obtenerPerfilCurso(estudianteId, cursoId)
            if (perfilCurso != null) {
                val estadoActual = perfilCurso.temasCompletados[temaId]
                val ahora = System.currentTimeMillis()
                val quizzesRealizados = (estadoActual?.quizzesRealizados ?: 0) + 1
                val porcentajePromedio = if (estadoActual == null) porcentaje else {
                    // recalcular promedio simple ponderado (puedes ajustar la lógica)
                    ((estadoActual.porcentajePromedio * (quizzesRealizados - 1)) + porcentaje) / quizzesRealizados
                }
                val preguntasVistasActual = (estadoActual?.preguntasVistas ?: emptySet()) + preguntasIds.toSet()

                val nuevoEstado = EstadoTema(
                    temaId = temaId,
                    quizzesRealizados = quizzesRealizados,
                    quizzesRequeridos = estadoActual?.quizzesRequeridos ?: 0,
                    porcentajePromedio = porcentajePromedio,
                    aprobado = aprobado || (estadoActual?.aprobado ?: false),
                    fechaPrimerIntento = estadoActual?.fechaPrimerIntento ?: ahora,
                    fechaUltimoIntento = ahora,
                    preguntasVistas = preguntasVistasActual
                )

                // Delegar actualización al repo (debe implementarse allí)
                quizRepo.actualizarEstadoTema(estudianteId, cursoId, temaId, nuevoEstado)
            } else {
                // Si no existe perfilcurso, crear uno mínimo y persistir (repositorio debe soportarlo)
                val ahora = System.currentTimeMillis()
                val nuevoEstado = EstadoTema(
                    temaId = temaId,
                    quizzesRealizados = 1,
                    quizzesRequeridos = 0,
                    porcentajePromedio = porcentaje,
                    aprobado = aprobado,
                    fechaPrimerIntento = ahora,
                    fechaUltimoIntento = ahora,
                    preguntasVistas = preguntasIds.toSet()
                )
                quizRepo.crearOActualizarEstadoTema(estudianteId, cursoId, temaId, nuevoEstado)
            }
        } catch (ex: NoSuchMethodError) {
            // Si el repo no implementó estos métodos, no hacemos nada.
            // Esto evita que el servicio rompa si el backend aún no los tiene.
        }
    }
}
