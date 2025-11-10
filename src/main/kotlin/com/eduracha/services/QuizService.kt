package com.eduracha.services

import com.eduracha.models.*
import com.eduracha.repository.*
import java.time.Instant
import kotlin.math.max

class QuizService(
    private val quizRepo: QuizRepository,
    private val preguntaRepo: PreguntaRepository,
    private val cursoRepo: CursoRepository,
    private val servicioSeleccion: ServicioSeleccionPreguntas,
    private val solicitudPreguntasRepo: SolicitudPreguntasRepository
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

    // INICIAR QUIZ CON LAS 3 VALIDACIONES
    
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

        // VALIDACIÓN 1: VERIFICAR PREGUNTAS MÍNIMAS
        
        val validacionMinimo = servicioSeleccion.puedePublicarTema(cursoId, temaId)
        if (!validacionMinimo.permitido) {
            throw IllegalStateException(
                "Este tema aún no está disponible para quizzes. ${validacionMinimo.mensaje}"
            )
        }

        // 3. Obtener estado del tema
        val estadoTema = obtenerEstadoTema(cursoId, temaId, userId)

        // 4. Validar programación
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

        // 5. Regenerar vidas
        val inscripcionActualizada = quizRepo.regenerarVidas(cursoId, inscripcion, VIDA_REGEN_MINUTOS)
        if (inscripcionActualizada.vidasActuales != inscripcion.vidasActuales) {
            quizRepo.actualizarInscripcion(cursoId, inscripcionActualizada)
        }

        // 6. Verificar vidas
        if (inscripcionActualizada.vidasActuales <= 0) {
            throw IllegalStateException("No tienes vidas disponibles. Espera ${VIDA_REGEN_MINUTOS} minutos para recuperar una vida.")
        }

        // 7. Verificar explicación vista
        val vioExplicacion = quizRepo.verificarExplicacionVista(userId, temaId)
        if (!vioExplicacion) {
            throw IllegalStateException("Debes ver la explicación antes de iniciar el quiz")
        }

        // VALIDACIÓN 2: VERIFICAR SI AGOTÓ PREGUNTAS
        
        val preguntasYaVistas = estadoTema?.preguntasVistas ?: emptySet()
        
        val agotoBanco = servicioSeleccion.estudianteVioTodasLasPreguntas(
            cursoId, temaId, preguntasYaVistas
        )
        
        if (agotoBanco) {
            // VALIDACIÓN 3: CREAR SOLICITUD AL DOCENTE
            
            val existeSolicitud = solicitudPreguntasRepo.existeSolicitudPendiente(
                cursoId, temaId, userId
            )
            
            if (!existeSolicitud) {
                val estudiante = obtenerDatosEstudiante(cursoId, userId)
                val tema = cursoRepo.obtenerTema(cursoId, temaId)
                
                val solicitud = SolicitudMasPreguntas(
                    cursoId = cursoId,
                    temaId = temaId,
                    tituloTema = tema?.titulo ?: "Sin título",
                    estudianteId = userId,
                    estudianteNombre = estudiante?.nombre ?: "Desconocido",
                    estudianteEmail = estudiante?.email,
                    preguntasActuales = preguntasYaVistas.size,
                    preguntasVistas = preguntasYaVistas.size,
                    mensaje = "El estudiante ha completado todas las preguntas disponibles del tema.",
                    prioridad = determinarPrioridad(estadoTema)
                )
                
                try {
                    solicitudPreguntasRepo.crearSolicitud(solicitud)
                    actualizarFlagNecesitaMasPreguntas(cursoId, temaId, userId, true)
                } catch (e: Exception) {
                    println(" Error al crear solicitud: ${e.message}")
                }
            }
            
            throw IllegalStateException(
                "Has completado todas las preguntas disponibles para este tema. " +
                "Se ha notificado al docente para que agregue más preguntas."
            )
        }


        // SELECCIÓN DE PREGUNTAS
        
        val preguntasSeleccionadas = servicioSeleccion.seleccionarPreguntasParaQuiz(
            cursoId = cursoId,
            temaId = temaId,
            estudianteId = userId,
            cantidadRequerida = 10,
            preguntasYaVistas = preguntasYaVistas
        )

        if (preguntasSeleccionadas.isEmpty()) {
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

    // FINALIZAR QUIZ
    
    suspend fun finalizarQuiz(
        quizId: String,
        respuestas: List<RespuestaUsuario>,
        userId: String
    ): FinalizarQuizResponse {
        val quiz = quizRepo.obtenerQuizPorId(quizId)
            ?: throw IllegalStateException("Quiz no encontrado")

        if (quiz.estudianteId != userId) {
            throw IllegalStateException("No tienes permiso para finalizar este quiz")
        }

        if (quiz.estado != "en_progreso") {
            throw IllegalStateException("Este quiz ya fue finalizado")
        }

        val respuestasEvaluadas = evaluarRespuestas(quiz, respuestas)
        val correctas = respuestasEvaluadas.count { it.esCorrecta }
        val incorrectas = respuestasEvaluadas.size - correctas

        val tiempoTotal = respuestas.sumOf { it.tiempoSeg }
        val tiempoPromedio = if (respuestas.isNotEmpty()) tiempoTotal.toDouble() / respuestas.size else 0.0

        val porcentaje = if (respuestas.isNotEmpty()) (correctas * 100) / respuestas.size else 0
        val aprobado = porcentaje >= PORCENTAJE_APROBACION

        val xpBase = correctas * XP_POR_RESPUESTA_CORRECTA
        val bonRapidez = if (tiempoPromedio < UMBRAL_RAPIDEZ_SEG) BONIFICACION_RAPIDEZ else 0
        val bonPerfect = if (incorrectas == 0 && correctas > 0) BONIFICACION_TODO_CORRECTO else 0

        val esPrimeraVez = quizRepo.esPrimeraVezAprobado(userId, quiz.cursoId, quiz.temaId)
        val bonPrimera = if (esPrimeraVez && aprobado) BONIFICACION_PRIMERA_VEZ else 0

        val xpTotal = xpBase + bonRapidez + bonPrimera + bonPerfect

        val inscripcion = quizRepo.obtenerInscripcion(quiz.cursoId, userId)
            ?: throw IllegalStateException("Inscripción no encontrada")

        val nuevasVidas = max(0, inscripcion.vidasActuales - incorrectas)
        val inscripcionActualizada = inscripcion.copy(
            vidasActuales = nuevasVidas,
            intentosHechos = inscripcion.intentosHechos + 1
        )
        quizRepo.actualizarInscripcion(quiz.cursoId, inscripcionActualizada)

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

        actualizarEstadoTema(
            cursoId = quiz.cursoId,
            temaId = quiz.temaId,
            estudianteId = userId,
            porcentaje = porcentaje,
            aprobado = aprobado,
            preguntasIds = quiz.preguntas.map { it.preguntaId }
        )

        if (aprobado) {
            ServicioProgreso.actualizarProgreso(userId, "quiz", true)
            ServicioProgreso.actualizarRacha(quiz.cursoId, userId, true)
        } else {
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

    // OBTENER REVISIÓN
    
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

    // MARCAR EXPLICACIÓN VISTA
    
    suspend fun marcarExplicacionVista(userId: String, temaId: String) {
        quizRepo.marcarExplicacionVista(userId, temaId)
    }

    // OBTENER VIDAS
  
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

    // OBTENER RETROALIMENTACIÓN DE FALLOS
    
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
                respuestaUsuarioTexto = respuestaUsuario?.texto ?: "Sin respuesta",
                respuestaCorrectaTexto = respuestaCorrecta?.texto ?: "Sin respuesta correcta",
                explicacion = pregunta.explicacionCorrecta ?: "No hay explicación disponible"
            )
        }

        return RetroalimentacionFallosResponse(
            quizId = quizId,
            totalFallos = detallesFallos.size,
            preguntasFalladas = detallesFallos
        )
    }

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

    private suspend fun obtenerEstadoTema(
        cursoId: String,
        temaId: String,
        estudianteId: String
    ): EstadoTema? {
        return try {
            val perfilCurso = quizRepo.obtenerPerfilCurso(estudianteId, cursoId)
            perfilCurso?.temasCompletados?.get(temaId)
        } catch (ex: Exception) {
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
        try {
            val perfilCurso = quizRepo.obtenerPerfilCurso(estudianteId, cursoId)
            if (perfilCurso != null) {
                val estadoActual = perfilCurso.temasCompletados[temaId]
                val ahora = System.currentTimeMillis()
                val quizzesRealizados = (estadoActual?.quizzesRealizados ?: 0) + 1
                val porcentajePromedio = if (estadoActual == null) porcentaje else {
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

                quizRepo.actualizarEstadoTema(estudianteId, cursoId, temaId, nuevoEstado)
            } else {
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
        } catch (ex: Exception) {
            println("Error actualizando estado del tema: ${ex.message}")
        }
    }

    private suspend fun obtenerDatosEstudiante(
        cursoId: String,
        estudianteId: String
    ): DatosEstudiante? {
        return try {
            val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
            val estudiante = estudiantes.find { 
                (it["id"] as? String) == estudianteId 
            }
            
            DatosEstudiante(
                id = estudianteId,
                nombre = estudiante?.get("nombre") as? String ?: "Desconocido",
                email = estudiante?.get("email") as? String
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun determinarPrioridad(estadoTema: EstadoTema?): String {
        return when {
            estadoTema == null -> "normal"
            estadoTema.aprobado -> "baja"
            estadoTema.quizzesRealizados >= 3 -> "alta"
            estadoTema.porcentajePromedio < 50 -> "urgente"
            else -> "normal"
        }
    }

    private suspend fun actualizarFlagNecesitaMasPreguntas(
        cursoId: String,
        temaId: String,
        estudianteId: String,
        necesita: Boolean
    ) {
        try {
            val estadoActual = quizRepo.obtenerEstadoTema(estudianteId, cursoId, temaId)
            if (estadoActual != null) {
                val estadoActualizado = estadoActual.copy(
                    necesitaMasPreguntas = necesita,
                    fechaSolicitudMasPreguntas = if (necesita) System.currentTimeMillis() else null
                )
                quizRepo.actualizarEstadoTema(estudianteId, cursoId, temaId, estadoActualizado)
            }
        } catch (e: Exception) {
            println("Error actualizando flag: ${e.message}")
        }
    }
}

data class DatosEstudiante(
    val id: String,
    val nombre: String,
    val email: String? = null
)