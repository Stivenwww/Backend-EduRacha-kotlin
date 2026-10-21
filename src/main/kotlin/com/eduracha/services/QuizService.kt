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

    suspend fun procesarRespuestaIndividual(
        quizId: String,
        preguntaId: String,
        respuestaSeleccionada: Int,
        tiempoSeg: Int,
        userId: String
    ): ProcesarRespuestaResponse {
        val quiz = quizRepo.obtenerQuizPorId(quizId)
            ?: throw IllegalStateException("Quiz no encontrado")

        if (quiz.estudianteId != userId) {
            throw IllegalStateException("No tienes permiso para este quiz")
        }

        if (quiz.estado != "en_progreso") {
            throw IllegalStateException("Este quiz ya fue finalizado o abandonado")
        }

        val pregunta = preguntaRepo.obtenerPreguntaPorId(preguntaId)
            ?: throw IllegalStateException("Pregunta no encontrada")

        val opcionCorrecta = pregunta.opciones.indexOfFirst { it.esCorrecta }
        val esCorrecta = respuestaSeleccionada == opcionCorrecta

        var vidasActuales = quiz.vidasIniciales ?: VIDAS_MAX

        val respuestasActuales = quiz.respuestas.size
        val correctasActuales = quiz.respuestas.count { it.esCorrecta }

        if (!esCorrecta) {
            vidasActuales -= 1

            println(" Respuesta INCORRECTA")
            println("   Quiz: $quizId")
            println("   Pregunta: $preguntaId")
            println("   Vidas: ${quiz.vidasIniciales} → $vidasActuales")
            println("   Modo: ${quiz.modo}")

            try {
                ServicioProgreso.actualizarVidasInmediato(userId, quiz.cursoId, vidasActuales)
                println(" Vidas actualizadas en Firebase: $vidasActuales")
            } catch (ex: Exception) {
                println(" Error actualizando vidas: ${ex.message}")
            }

            if (vidasActuales <= 0) {
                println("========================================")
                println(" VIDAS AGOTADAS - ABANDONANDO QUIZ")
                println("========================================")
                println("Quiz ID: $quizId")
                println("Modo: ${quiz.modo}")
                println("Preguntas respondidas: ${respuestasActuales + 1}")
                println("Correctas: $correctasActuales")
                println("========================================")

                val nuevaRespuesta = RespuestaQuiz(
                    preguntaId = preguntaId,
                    respuestaSeleccionada = respuestaSeleccionada,
                    tiempoSeg = tiempoSeg,
                    esCorrecta = false
                )

                val respuestasFinales = quiz.respuestas + nuevaRespuesta

                val quizAbandonado = quiz.copy(
                    estado = "abandonado",
                    fin = Instant.now().toString(),
                    vidasFinales = 0,
                    respuestas = respuestasFinales,
                    preguntasCorrectas = respuestasFinales.count { it.esCorrecta },
                    preguntasIncorrectas = respuestasFinales.count { !it.esCorrecta }
                )

                try {
                    quizRepo.actualizarQuiz(quizAbandonado)
                    println(" Quiz abandonado guardado en Firebase")
                } catch (ex: Exception) {
                    println("Error guardando quiz abandonado: ${ex.message}")
                }

                throw QuizAbandonadoPorVidasException(
                    " Te has quedado sin vidas. El quiz se ha detenido.\n" +
                    " Respondiste correctamente ${respuestasFinales.count { it.esCorrecta }} de ${respuestasFinales.size} preguntas.\n" +
                    " Las vidas se recuperan cada $VIDA_REGEN_MINUTOS minutos."
                )
            }
        } else {
            println(" Respuesta CORRECTA")
            println("   Quiz: $quizId")
            println("   Pregunta: $preguntaId")
            println("   Vidas: $vidasActuales/$VIDAS_MAX")
        }

        val nuevaRespuesta = RespuestaQuiz(
            preguntaId = preguntaId,
            respuestaSeleccionada = respuestaSeleccionada,
            tiempoSeg = tiempoSeg,
            esCorrecta = esCorrecta
        )

        val quizActualizado = quiz.copy(
            respuestas = quiz.respuestas + nuevaRespuesta,
            vidasIniciales = vidasActuales
        )

        quizRepo.actualizarQuiz(quizActualizado)

        return ProcesarRespuestaResponse(
            esCorrecta = esCorrecta,
            vidasRestantes = vidasActuales,
            quizActivo = true,
            preguntasRespondidas = respuestasActuales + 1,
            preguntasCorrectas = if (esCorrecta) correctasActuales + 1 else correctasActuales
        )
    }


    suspend fun iniciarQuiz(
        cursoId: String,
        temaId: String,
        userId: String,
        modoSolicitado: String = "auto"
    ): IniciarQuizResponse {
        val curso = cursoRepo.obtenerCursoPorId(cursoId)
            ?: throw IllegalStateException("Curso no encontrado")

        val programacion = curso.programacion
            ?: throw IllegalStateException("El curso no tiene programación configurada")

        val progreso = ServicioProgreso.obtenerOReconstruirProgreso(userId, cursoId)
            ?: throw IllegalStateException("No estás inscrito en este curso")

        val validacionMinimo = servicioSeleccion.puedePublicarTema(cursoId, temaId)
        if (!validacionMinimo.permitido) {
            throw IllegalStateException(
                "Este tema aún no está disponible para quizzes. ${validacionMinimo.mensaje}"
            )
        }

        val estadoTema = obtenerEstadoTema(cursoId, temaId, userId)

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

        ServicioProgreso.regenerarVidas(userId, cursoId, VIDA_REGEN_MINUTOS)

        val progresoActualizado = ServicioProgreso.obtenerProgreso(userId, cursoId)
            ?: throw IllegalStateException("Error al obtener progreso")

        val vidasActuales = (progresoActualizado["vidas"] as? Number)?.toInt() ?: VIDAS_MAX

        if (vidasActuales <= 0) {
            throw IllegalStateException(
                "No tienes vidas disponibles. Espera $VIDA_REGEN_MINUTOS minutos para recuperar una vida."
            )
        }

        val vioExplicacion = explicacionVigenteVista(
            userId, temaId, curso.temas?.get(temaId)?.explicacion
        )
        if (!vioExplicacion) {
            throw IllegalStateException("Debes ver la explicación antes de iniciar el quiz")
        }

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

        val temaAprobado = estadoTema?.aprobado ?: false

        val modo = when {
            modoSolicitado == "practica" -> "practica"
            temaId == "quiz_final" -> "final"
            !temaAprobado -> "oficial"
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

        println("========================================")
        println("FINALIZANDO QUIZ")
        println("Quiz ID: $quizId")
        println("Respuestas del usuario: ${respuestas.size}")
        println("Respuestas en quiz: ${quiz.respuestas.size}")
        println("Modo: ${quiz.modo}")
        println("========================================")

        val respuestasEvaluadas = quiz.respuestas

        if (respuestasEvaluadas.isEmpty()) {
            throw IllegalStateException("No hay respuestas evaluadas en el quiz")
        }

        println("  Respuestas evaluadas: ${respuestasEvaluadas.size}")
        
        val correctas = respuestasEvaluadas.count { it.esCorrecta }
        val incorrectas = respuestasEvaluadas.size - correctas
        val porcentaje = if (respuestasEvaluadas.isNotEmpty()) {
            (correctas * 100) / respuestasEvaluadas.size
        } else 0
        val aprobado = porcentaje >= PORCENTAJE_APROBACION

        println("  Correctas: $correctas")
        println("  Incorrectas: $incorrectas")
        println("  Porcentaje: $porcentaje%")
        println("  Aprobado: $aprobado")

        val vidasFinales = quiz.vidasIniciales ?: run {
            val progreso = ServicioProgreso.obtenerProgreso(userId, quiz.cursoId)
                ?: throw IllegalStateException("Progreso no encontrado")
            (progreso["vidas"] as? Number)?.toInt() ?: VIDAS_MAX
        }

        println("  Vidas finales: $vidasFinales")

        val xpBase = correctas * XP_POR_RESPUESTA_CORRECTA
        val tiempoTotal = respuestas.sumOf { it.tiempoSeg }
        val tiempoPromedio = if (respuestas.isNotEmpty()) {
            tiempoTotal.toDouble() / respuestas.size
        } else 0.0

        val bonRapidez = if (tiempoPromedio < UMBRAL_RAPIDEZ_SEG) BONIFICACION_RAPIDEZ else 0
        val bonPerfect = if (incorrectas == 0 && correctas > 0) BONIFICACION_TODO_CORRECTO else 0

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
                    actualizarRacha = true,
                    esPractica = false
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
                    actualizarRacha = false,
                    esPractica = false
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
                    actualizarRacha = false,
                    esPractica = false
                )

                if (aprobado) {
                    cursoRepo.marcarCursoCompletado(quiz.cursoId, userId)
                }

                Pair(xpTotalLocal, 0)
            }
            else -> Pair(xpBase, 0)
        }

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

        actualizarEstadoTema(
            cursoId = quiz.cursoId,
            temaId = quiz.temaId,
            estudianteId = userId,
            porcentaje = porcentaje,
            aprobado = aprobado && (quiz.modo == "oficial" || quiz.modo == "final"),
            preguntasIds = quiz.preguntas.map { it.preguntaId },
            modo = quiz.modo
        )

        if (aprobado && quiz.modo == "oficial") {
            try {
                val curso = cursoRepo.obtenerCursoPorId(quiz.cursoId)
                val totalTemas = curso?.temas?.size ?: 0

                if (totalTemas > 0) {
                    ServicioProgreso.actualizarPorcentajeCurso(
                        usuarioId = userId,
                        cursoId = quiz.cursoId,
                        totalTemas = totalTemas
                    )
                }
            } catch (e: Exception) {
                println("Error actualizando porcentaje: ${e.message}")
            }
        }

        println("  Quiz finalizado exitosamente")
        println("  XP ganado: $xpTotal")
        println("  Vidas finales: $vidasFinales")
        println("========================================")

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

    suspend fun obtenerVidas(cursoId: String, userId: String): Map<String, Any> {
        ServicioProgreso.regenerarVidas(userId, cursoId, VIDA_REGEN_MINUTOS)

        val progreso = ServicioProgreso.obtenerOReconstruirProgreso(userId, cursoId)
            ?: throw IllegalStateException("No estás inscrito en este curso")

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

    /**
     * La explicación es obligatoria solo la primera vez. Después queda como repaso opcional,
     * salvo que el docente la haya cambiado (huella distinta): entonces se pide leer la nueva.
     * Registros antiguos sin huella se consideran vigentes.
     */
    suspend fun explicacionVigenteVista(userId: String, temaId: String, explicacionActual: String?): Boolean {
        val registro = quizRepo.obtenerRegistroExplicacion(userId, temaId)
        if (!registro.visto) return false
        val actual = com.eduracha.utils.TextoPlano.huella(explicacionActual) ?: return true
        return registro.huella == null || registro.huella == actual
    }

    /** Estado para la app: si ya la vio, y si la explicación cambió desde entonces. */
    suspend fun estadoExplicacion(userId: String, cursoId: String, temaId: String): Map<String, Boolean> {
        val registro = quizRepo.obtenerRegistroExplicacion(userId, temaId)
        val explicacion = cursoRepo.obtenerTema(cursoId, temaId)?.explicacion
        val actual = com.eduracha.utils.TextoPlano.huella(explicacion)
        val cambio = registro.visto && actual != null && registro.huella != null && registro.huella != actual
        return mapOf(
            "vista" to (registro.visto && !cambio),
            "cambio" to cambio
        )
    }

    suspend fun marcarExplicacionVista(userId: String, temaId: String, cursoId: String? = null) {
        val huella = cursoId?.takeIf { it.isNotBlank() }?.let {
            com.eduracha.utils.TextoPlano.huella(cursoRepo.obtenerTema(it, temaId)?.explicacion)
        }
        quizRepo.marcarExplicacionVista(userId, temaId, huella)
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

    suspend fun iniciarQuizFinal(cursoId: String, userId: String): IniciarQuizResponse {
        val curso = cursoRepo.obtenerCursoPorId(cursoId)
            ?: throw IllegalStateException("Curso no encontrado")

        val idsTemas = curso.temas?.map { (clave, tema) -> tema.id ?: clave }?.toSet() ?: emptySet()
        val aprobadosDelCurso = temasAprobados(userId, cursoId).intersect(idsTemas)
        val totalTemas = idsTemas.size

        if (totalTemas == 0 || aprobadosDelCurso.size < totalTemas) {
            throw IllegalStateException(
                "Aún no has aprobado todos los temas.\n" +
                "Progreso: ${aprobadosDelCurso.size}/$totalTemas temas completados"
            )
        }

        // El quiz final se aprueba una sola vez: si ya está aprobado no se vuelve a abrir
        val estadoFinal = try { quizRepo.leerEstadoTemaCrudo(userId, cursoId, "quiz_final") } catch (e: Exception) { null }
        val finalYaAprobado = (estadoFinal?.get("aprobado") as? Boolean ?: false) ||
            quizRepo.obtenerQuizzesPorEstudiante(userId, cursoId).any { q ->
                val total = q.preguntasCorrectas + q.preguntasIncorrectas
                q.modo == "final" && q.estado == "finalizado" && total > 0 &&
                    (q.preguntasCorrectas * 100) / total >= PORCENTAJE_APROBACION
            }
        if (finalYaAprobado) {
            throw IllegalStateException("Ya aprobaste el quiz final de este curso.")
        }

        val progreso = ServicioProgreso.obtenerOReconstruirProgreso(userId, cursoId)
            ?: throw IllegalStateException("No estás inscrito en este curso")

        val vidasActuales = (progreso["vidas"] as? Number)?.toInt() ?: VIDAS_MAX

        if (vidasActuales <= 0) {
            throw IllegalStateException(
                "No tienes vidas disponibles para el quiz final."
            )
        }

        val preguntasSeleccionadas = curso.temas!!.values.flatMap { tema ->
            val preguntasDelTema = preguntaRepo.obtenerPreguntasPorCursoYEstado(cursoId, "aprobada")
                .filter { it.temaId == tema.id }

            preguntasDelTema.shuffled().take(2)
        }

        if (preguntasSeleccionadas.isEmpty()) {
            throw IllegalStateException("No hay preguntas disponibles para el quiz final")
        }

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

    /**
     * Guarda el resultado del quiz en usuarios/{uid}/cursos/{cursoId}/temasCompletados/{temaId}.
     * - Solo actualiza los campos que cambian (no borra los que escribe la app).
     * - "aprobado" nunca vuelve a false y "porcentajeObtenido" guarda el mejor resultado oficial/final.
     * - Las prácticas suman intentos y preguntas vistas, pero no cambian la aprobación.
     */
    private suspend fun actualizarEstadoTema(
        cursoId: String,
        temaId: String,
        estudianteId: String,
        porcentaje: Int,
        aprobado: Boolean,
        preguntasIds: List<String>,
        modo: String
    ) {
        try {
            val actual = quizRepo.leerEstadoTemaCrudo(estudianteId, cursoId, temaId) ?: emptyMap()
            fun entero(c: String) = (actual[c] as? Number)?.toInt() ?: 0
            val ahora = System.currentTimeMillis()

            val realizados = entero("quizzesRealizados") + 1
            val promedio = ((entero("porcentajePromedio") * (realizados - 1)) + porcentaje) / realizados
            val vistasPrevias: Set<String> = when (val v = actual["preguntasVistas"]) {
                is List<*> -> v.mapNotNull { it?.toString() }.toSet()
                is Map<*, *> -> v.values.mapNotNull { it?.toString() }.toSet()
                else -> emptySet()
            }
            val yaAprobado = actual["aprobado"] as? Boolean ?: false

            val campos = mutableMapOf<String, Any?>(
                "temaId" to temaId,
                "quizzesRealizados" to realizados,
                "porcentajePromedio" to promedio,
                "aprobado" to (yaAprobado || aprobado),
                "fechaPrimerIntento" to ((actual["fechaPrimerIntento"] as? Number)?.toLong() ?: ahora),
                "fechaUltimoIntento" to ahora,
                "preguntasVistas" to (vistasPrevias + preguntasIds).toList()
            )
            if (modo != "practica") {
                campos["porcentajeObtenido"] = max(entero("porcentajeObtenido"), porcentaje)
                if (aprobado) {
                    campos["timestampUltimoQuiz"] = ahora
                    campos["ultimaFechaQuiz"] = java.time.LocalDate.now().toString()
                }
            }
            quizRepo.actualizarCamposEstadoTema(estudianteId, cursoId, temaId, campos)
        } catch (ex: Exception) {
            println("Error actualizando estado del tema $temaId: ${ex.message}")
        }
    }

    /**
     * Temas aprobados del estudiante: los marcados en temasCompletados o con un quiz oficial
     * finalizado con 80% o más (misma regla que GET /quiz/aprobados, que usa el mapa de islas).
     */
    suspend fun temasAprobados(userId: String, cursoId: String): Set<String> {
        val desdePerfil = try {
            quizRepo.obtenerPerfilCurso(userId, cursoId)?.temasCompletados
                ?.filter { it.value.aprobado }?.keys ?: emptySet()
        } catch (e: Exception) { emptySet() }
        val desdeQuizzes = try {
            quizRepo.obtenerQuizzesPorEstudiante(userId, cursoId)
                .filter { q ->
                    val total = q.preguntasCorrectas + q.preguntasIncorrectas
                    q.estado == "finalizado" && q.modo != "final" && q.modo != "practica" &&
                        total > 0 && (q.preguntasCorrectas * 100) / total >= PORCENTAJE_APROBACION
                }
                .map { it.temaId }
                .toSet()
        } catch (e: Exception) { emptySet() }
        return (desdePerfil + desdeQuizzes) - "quiz_final"
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

    private fun determinarPrioridad(estadoTema: EstadoTema?):
    String {
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


class QuizAbandonadoPorVidasException(message: String) : IllegalStateException(message)