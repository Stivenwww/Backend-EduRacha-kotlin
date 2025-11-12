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

    // INICIAR QUIZ
    suspend fun iniciarQuiz(
        cursoId: String,
        temaId: String,
        userId: String,
        modoSolicitado: String = "auto" // "auto", "oficial", "practica"
    ): IniciarQuizResponse {
        // 1. Obtener curso y programación
        val curso = cursoRepo.obtenerCursoPorId(cursoId)
            ?: throw IllegalStateException("Curso no encontrado")

        val programacion = curso.programacion
            ?: throw IllegalStateException("El curso no tiene programación configurada")

        // 2. Verificar progreso
        val progreso = ServicioProgreso.obtenerProgreso(userId, cursoId)
            ?: throw IllegalStateException("No estás inscrito en este curso")

        // 3. Validación mínimo de preguntas
        val validacionMinimo = servicioSeleccion.puedePublicarTema(cursoId, temaId)
        if (!validacionMinimo.permitido) {
            throw IllegalStateException(
                "Este tema aún no está disponible para quizzes. ${validacionMinimo.mensaje}"
            )
        }

        // 4. Obtener estado del tema
        val estadoTema = obtenerEstadoTema(cursoId, temaId, userId)

        // 5. Validar programación
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

        // 6. Regenerar vidas antes de verificar
        ServicioProgreso.regenerarVidas(userId, cursoId, VIDA_REGEN_MINUTOS)

        // Obtener progreso actualizado
        val progresoActualizado = ServicioProgreso.obtenerProgreso(userId, cursoId)
            ?: throw IllegalStateException("Error al obtener progreso")

        val vidasActuales = (progresoActualizado["vidas"] as? Number)?.toInt() ?: VIDAS_MAX

        // 7. Verificar vidas antes de crear el quiz
        if (vidasActuales <= 0) {
            throw IllegalStateException("No tienes vidas disponibles. Espera $VIDA_REGEN_MINUTOS minutos para recuperar una vida.")
        }

        // 8. Verificar explicación vista
        val vioExplicacion = quizRepo.verificarExplicacionVista(userId, temaId)
        if (!vioExplicacion) {
            throw IllegalStateException("Debes ver la explicación antes de iniciar el quiz")
        }

        // 9. Validar preguntas disponibles
        val preguntasYaVistas = estadoTema?.preguntasVistas ?: emptySet()

        val agotoBanco = servicioSeleccion.estudianteVioTodasLasPreguntas(
            cursoId, temaId, preguntasYaVistas
        )

        if (agotoBanco) {
            val existeSolicitud = solicitudPreguntasRepo.existeSolicitudPendiente(
                cursoId, temaId, userId
            )

            if (!existeSolicitud) {
                crearSolicitudMasPreguntas(cursoId, temaId, userId, estadoTema, preguntasYaVistas)
            }

            throw IllegalStateException(
                "Has completado todas las preguntas disponibles para este tema. " +
                "Se ha notificado al docente para que agregue más preguntas."
            )
        }

        // 10. Seleccionar preguntas
        val preguntasSeleccionadas = servicioSeleccion.seleccionarPreguntasParaQuiz(
            cursoId = cursoId,
            temaId = temaId,
            estudianteId = userId,
            cantidadRequerida = 10,
            preguntasYaVistas = preguntasYaVistas
        )

        if (preguntasSeleccionadas.isEmpty()) {
            throw IllegalStateException("No hay preguntas disponibles para este tema")
        }

        // DETERMINAR MODO DEL QUIZ
        val temaAprobado = estadoTema?.aprobado ?: false

        val modo = when {
            modoSolicitado == "practica" -> "practica"
            temaId == "quiz_final" -> "final"
            !temaAprobado -> "oficial" // primera vez obligatorio oficial
            else -> modoSolicitado.takeIf { it in listOf("oficial", "practica") } ?: "practica"
        }

        return crearYResponderQuiz(
            cursoId = cursoId,
            temaId = temaId,
            userId = userId,
            preguntas = preguntasSeleccionadas,
            estadoTema = estadoTema,
            modo = modo,
            vidasIniciales = vidasActuales
        )
    }

    // FINALIZAR QUIZ - controla los tres modos y usa control de vidas en tiempo real
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

        // Evaluar respuestas con control de vidas en tiempo real
        val (respuestasEvaluadas, vidasFinales) = evaluarRespuestas(quiz, respuestas, userId)

        val correctas = respuestasEvaluadas.count { it.esCorrecta }
        val incorrectas = respuestasEvaluadas.size - correctas
        val porcentaje = if (respuestas.isNotEmpty()) (correctas * 100) / respuestas.size else 0
        val aprobado = porcentaje >= PORCENTAJE_APROBACION

        // Calcular experiencia base y bonificaciones
        val xpBase = correctas * XP_POR_RESPUESTA_CORRECTA
        val tiempoTotal = respuestas.sumOf { it.tiempoSeg }
        val tiempoPromedio = if (respuestas.isNotEmpty()) tiempoTotal.toDouble() / respuestas.size else 0.0

        val bonRapidez = if (tiempoPromedio < UMBRAL_RAPIDEZ_SEG) BONIFICACION_RAPIDEZ else 0
        val bonPerfect = if (incorrectas == 0 && correctas > 0) BONIFICACION_TODO_CORRECTO else 0

        // Lógica según modo
        val (xpTotal, bonPrimera) = when (quiz.modo) {
            "oficial" -> {
                val esPrimeraVez = quizRepo.esPrimeraVezAprobado(userId, quiz.cursoId, quiz.temaId)
                val bonPrimeraLocal = if (esPrimeraVez && aprobado) BONIFICACION_PRIMERA_VEZ else 0
                val xpTotalLocal = xpBase + bonRapidez + bonPrimeraLocal + bonPerfect

                ServicioProgreso.actualizarProgresoQuiz(
                    usuarioId = userId,
                    cursoId = quiz.cursoId,
                    xpGanado = xpTotalLocal,
                    vidasPerdidas = 0, 
                    aprobado = aprobado,
                    actualizarRacha = true
                )

                Pair(xpTotalLocal, bonPrimeraLocal)
            }

            "practica" -> {
                val xpTotalLocal = xpBase + bonRapidez + bonPerfect

                ServicioProgreso.actualizarProgresoQuiz(
                    usuarioId = userId,
                    cursoId = quiz.cursoId,
                    xpGanado = xpTotalLocal,
                    vidasPerdidas = 0,
                    aprobado = false,
                    actualizarRacha = false
                )

                Pair(xpTotalLocal, 0)
            }

            "final" -> {
                val BONIFICACION_FINAL = 100
                val xpTotalLocal = xpBase + bonRapidez + bonPerfect + BONIFICACION_FINAL

                ServicioProgreso.actualizarProgresoQuiz(
                    usuarioId = userId,
                    cursoId = quiz.cursoId,
                    xpGanado = xpTotalLocal,
                    vidasPerdidas = 0,
                    aprobado = aprobado,
                    actualizarRacha = false
                )

                if (aprobado) {
                    cursoRepo.marcarCursoCompletado(quiz.cursoId, userId)
                }

                Pair(xpTotalLocal, 0)
            }

            else -> Pair(xpBase, 0)
        }

        // Guardar quiz finalizado
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
            respuestas = respuestasEvaluadas,
            vidasFinales = vidasFinales
        )
        quizRepo.actualizarQuiz(quizFinalizado)

        // Actualizar estado del tema solo en modos oficiales y final
        if (quiz.modo != "practica") {
            actualizarEstadoTema(
                cursoId = quiz.cursoId,
                temaId = quiz.temaId,
                estudianteId = userId,
                porcentaje = porcentaje,
                aprobado = aprobado,
                preguntasIds = quiz.preguntas.map { it.preguntaId }
            )
        }

        return FinalizarQuizResponse(
            preguntasCorrectas = correctas,
            preguntasIncorrectas = incorrectas,
            experienciaGanada = xpTotal,
            vidasRestantes = vidasFinales,
            bonificaciones = BonificacionesResponse(
                rapidez = bonRapidez,
                primeraVez = bonPrimera,
                todoCorrecto = bonPerfect
            )
        )
    }

    // Obtener vidas 
    suspend fun obtenerVidas(cursoId: String, userId: String): Map<String, Any> {
        ServicioProgreso.regenerarVidas(userId, cursoId, VIDA_REGEN_MINUTOS)

        val progreso = ServicioProgreso.obtenerProgreso(userId, cursoId)
            ?: throw IllegalStateException("Progreso no encontrado")

        val vidasActuales = (progreso["vidas"] as? Number)?.toInt() ?: 5
        val vidasMax = (progreso["vidasMax"] as? Number)?.toInt() ?: 5
        val ultimaRegen = (progreso["ultimaRegen"] as? Number)?.toLong() ?: System.currentTimeMillis()

        val ahora = System.currentTimeMillis()
        val minutosParaProximaVida = if (vidasActuales < vidasMax) {
            VIDA_REGEN_MINUTOS - ((ahora - ultimaRegen) / (1000 * 60)).toInt()
        } else {
            0
        }

        return mapOf(
            "vidasActuales" to vidasActuales,
            "vidasMax" to vidasMax,
            "minutosParaProximaVida" to max(0, minutosParaProximaVida)
        )
    }

    // Obtener revisión 
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

    suspend fun marcarExplicacionVista(userId: String, temaId: String) {
        quizRepo.marcarExplicacionVista(userId, temaId)
    }

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
                ?: throw IllegalStateException("Pregunta no encontrada")

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

    // Evaluar respuestas: resta vidas en tiempo real y devuelve las respuestas evaluadas junto con las vidas finales
    private suspend fun evaluarRespuestas(
        quiz: Quiz,
        respuestasUsuario: List<RespuestaUsuario>,
        userId: String
    ): Pair<List<RespuestaQuiz>, Int> {

        var vidasActuales = quiz.vidasIniciales ?: run {
            // si el modelo quiz no tiene vidasIniciales, obtener desde progreso
            val progreso = ServicioProgreso.obtenerProgreso(userId, quiz.cursoId)
                ?: throw IllegalStateException("Progreso no encontrado")
            (progreso["vidas"] as? Number)?.toInt() ?: VIDAS_MAX
        }

        val respuestasEvaluadas = mutableListOf<RespuestaQuiz>()

        for (respuesta in respuestasUsuario) {
            val pregunta = preguntaRepo.obtenerPreguntaPorId(respuesta.preguntaId)
                ?: throw IllegalStateException("Pregunta no encontrada")

            val opcionCorrecta = pregunta.opciones.indexOfFirst { it.esCorrecta }
            val esCorrecta = respuesta.respuestaSeleccionada == opcionCorrecta

            // restar vida inmediatamente si falla
            if (!esCorrecta) {
                vidasActuales -= 1

                // actualizar vidas en Firebase en tiempo real
                try {
                    ServicioProgreso.actualizarVidasInmediato(userId, quiz.cursoId, vidasActuales)
                } catch (ex: Exception) {
                    // si falla la actualización en tiempo real, continuar pero registrar
                    println("Error actualizando vidas en tiempo real: ${ex.message}")
                }

                // si llega a 0, detener quiz: marcar como abandonado y persistir respuestas parciales
                if (vidasActuales <= 0) {
                    val quizAbandonado = quiz.copy(
                        estado = "abandonado",
                        fin = Instant.now().toString(),
                        vidasFinales = 0,
                        respuestas = respuestasEvaluadas
                    )
                    try {
                        quizRepo.actualizarQuiz(quizAbandonado)
                    } catch (ex: Exception) {
                        println("Error guardando quiz abandonado: ${ex.message}")
                    }

                    throw IllegalStateException(
                        "Te has quedado sin vidas. El quiz se ha detenido.\n" +
                        "Respondiste correctamente ${respuestasEvaluadas.count { it.esCorrecta }} preguntas."
                    )
                }
            }

            respuestasEvaluadas.add(
                RespuestaQuiz(
                    preguntaId = respuesta.preguntaId,
                    respuestaSeleccionada = respuesta.respuestaSeleccionada,
                    tiempoSeg = respuesta.tiempoSeg,
                    esCorrecta = esCorrecta
                )
            )
        }

        return Pair(respuestasEvaluadas, vidasActuales)
    }

    private suspend fun verificarVidasDuranteQuiz(
        userId: String,
        cursoId: String
    ): Int {
        val progreso = ServicioProgreso.obtenerProgreso(userId, cursoId)
            ?: throw IllegalStateException("Progreso no encontrado")

        val vidasActuales = (progreso["vidas"] as? Number)?.toInt() ?: 0

        if (vidasActuales <= 0) {
            throw IllegalStateException("Te has quedado sin vidas. El quiz se ha detenido.")
        }

        return vidasActuales
    }

    // Crear y responder quiz: ahora acepta modo y vidasIniciales opcionales para persistir esa info
    private suspend fun crearYResponderQuiz(
        cursoId: String,
        temaId: String,
        userId: String,
        preguntas: List<Pregunta>,
        estadoTema: EstadoTema?,
        modo: String = "practica",
        vidasIniciales: Int? = null
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
            intentoNumero = (estadoTema?.quizzesRealizados ?: 0) + 1,
            modo = modo,
            vidasIniciales = vidasIniciales ?: VIDAS_MAX
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

    // Iniciar quiz final: método que crea un quiz integrador tomando preguntas de todos los temas
    suspend fun iniciarQuizFinal(cursoId: String, userId: String): IniciarQuizResponse {

        // 1. Obtener curso
        val curso = cursoRepo.obtenerCursoPorId(cursoId)
            ?: throw IllegalStateException("Curso no encontrado")

        // 2. Verificar que todos los temas estén aprobados
        val perfilCurso = quizRepo.obtenerPerfilCurso(userId, cursoId)
            ?: throw IllegalStateException("No se encontró progreso del curso")

        val temasAprobados = perfilCurso.temasCompletados.values.filter { it.aprobado }
        val totalTemas = curso.temas?.size ?: 0

        if (temasAprobados.size < totalTemas) {
            throw IllegalStateException(
                "Aún no has aprobado todos los temas.\n" +
                "Progreso: ${temasAprobados.size}/$totalTemas temas completados"
            )
        }

        // 3. Verificar vidas
        val progreso = ServicioProgreso.obtenerProgreso(userId, cursoId)
            ?: throw IllegalStateException("Progreso no encontrado")

        val vidasActuales = (progreso["vidas"] as? Number)?.toInt() ?: VIDAS_MAX

        if (vidasActuales <= 0) {
            throw IllegalStateException(
                "No tienes vidas disponibles para el quiz final."
            )
        }

        // 4. Seleccionar preguntas de todos los temas (2 por tema)
        val preguntasSeleccionadas = curso.temas!!.values.flatMap { tema ->
            val preguntasDelTema = preguntaRepo.obtenerPreguntasPorCursoYEstado(cursoId, "aprobada")
                .filter { it.temaId == tema.id }

            preguntasDelTema.shuffled().take(2)
        }

        if (preguntasSeleccionadas.isEmpty()) {
            throw IllegalStateException("No hay preguntas disponibles para el quiz final")
        }

        // 5. Crear quiz final (modo 'final')
        return crearYResponderQuiz(
            cursoId = cursoId,
            temaId = "quiz_final",
            userId = userId,
            preguntas = preguntasSeleccionadas,
            estadoTema = null,
            modo = "final",
            vidasIniciales = vidasActuales
        )
    }

suspend fun obtenerEstadoTema(
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
            }
        } catch (ex: Exception) {
            println("Error actualizando estado del tema: ${ex.message}")
        }
    }

    private suspend fun crearSolicitudMasPreguntas(
        cursoId: String,
        temaId: String,
        userId: String,
        estadoTema: EstadoTema?,
        preguntasVistas: Set<String>
    ) {
        val estudiante = obtenerDatosEstudiante(cursoId, userId)
        val tema = cursoRepo.obtenerTema(cursoId, temaId)

        val solicitud = SolicitudMasPreguntas(
            cursoId = cursoId,
            temaId = temaId,
            tituloTema = tema?.titulo ?: "Sin título",
            estudianteId = userId,
            estudianteNombre = estudiante?.nombre ?: "Desconocido",
            estudianteEmail = estudiante?.email,
            preguntasActuales = preguntasVistas.size,
            preguntasVistas = preguntasVistas.size,
            mensaje = "El estudiante ha completado todas las preguntas disponibles.",
            prioridad = determinarPrioridad(estadoTema)
        )

        try {
            solicitudPreguntasRepo.crearSolicitud(solicitud)
        } catch (e: Exception) {
            println("Error al crear solicitud: ${e.message}")
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
}

data class DatosEstudiante(
    val id: String,
    val nombre: String,
    val email: String? = null
)
