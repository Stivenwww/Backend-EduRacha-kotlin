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

        val progreso = ServicioProgreso.obtenerProgreso(userId, cursoId)
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

        //  Regenerar vidas antes de verificar
        ServicioProgreso.regenerarVidas(userId, cursoId, VIDA_REGEN_MINUTOS)

        val progresoActualizado = ServicioProgreso.obtenerProgreso(userId, cursoId)
            ?: throw IllegalStateException("Error al obtener progreso")

        val vidasActuales = (progresoActualizado["vidas"] as? Number)?.toInt() ?: VIDAS_MAX

        //  Todos los modos requieren vidas
        if (vidasActuales <= 0) {
            throw IllegalStateException(
                "No tienes vidas disponibles. Espera $VIDA_REGEN_MINUTOS minutos para recuperar una vida."
            )
        }

        val vioExplicacion = quizRepo.verificarExplicacionVista(userId, temaId)
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

        // Evaluar respuestas y manejar vidas en tiempo real
        val (respuestasEvaluadas, vidasFinales, vidasPerdidas) = evaluarRespuestas(quiz, respuestas, userId)

        val correctas = respuestasEvaluadas.count { it.esCorrecta }
        val incorrectas = respuestasEvaluadas.size - correctas
        val porcentaje = if (respuestas.isNotEmpty()) (correctas * 100) / respuestas.size else 0
        val aprobado = porcentaje >= PORCENTAJE_APROBACION

        val xpBase = correctas * XP_POR_RESPUESTA_CORRECTA
        val tiempoTotal = respuestas.sumOf { it.tiempoSeg }
        val tiempoPromedio = if (respuestas.isNotEmpty()) tiempoTotal.toDouble() / respuestas.size else 0.0

        val bonRapidez = if (tiempoPromedio < UMBRAL_RAPIDEZ_SEG) BONIFICACION_RAPIDEZ else 0
        val bonPerfect = if (incorrectas == 0 && correctas > 0) BONIFICACION_TODO_CORRECTO else 0

        //  Calcular XP y actualizar progreso según modo
        val (xpTotal, bonPrimera) = when (quiz.modo) {
            "oficial" -> {
                val esPrimeraVez = quizRepo.esPrimeraVezAprobado(userId, quiz.cursoId, quiz.temaId)
                val bonPrimeraLocal = if (esPrimeraVez && aprobado) BONIFICACION_PRIMERA_VEZ else 0
                val xpTotalLocal = xpBase + bonRapidez + bonPrimeraLocal + bonPerfect

                println(" Quiz OFICIAL - XP: $xpTotalLocal, Aprobado: $aprobado, Primera vez: $esPrimeraVez")

                
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
                // Práctica da menos XP (sin bonificación de primera vez)
                val xpTotalLocal = xpBase + bonRapidez + bonPerfect

                println(" Quiz PRÁCTICA - XP: $xpTotalLocal, Aprobado: $aprobado")

                ServicioProgreso.actualizarProgresoQuiz(
                    usuarioId = userId,
                    cursoId = quiz.cursoId,
                    xpGanado = xpTotalLocal,
                    vidasPerdidas = 0,
                    aprobado = false, // Práctica NO marca tema como aprobado
                    actualizarRacha = false, //  Práctica NO actualiza racha
                    esPractica = false 
                )

                Pair(xpTotalLocal, 0)
            }

            "final" -> {
                val BONIFICACION_FINAL = 100
                val xpTotalLocal = xpBase + bonRapidez + bonPerfect + BONIFICACION_FINAL

                println("Quiz FINAL - XP: $xpTotalLocal, Aprobado: $aprobado")

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

        // SIEMPRE actualizar estado del tema (tanto oficial como práctica cuentan para progreso)
        actualizarEstadoTema(
            cursoId = quiz.cursoId,
            temaId = quiz.temaId,
            estudianteId = userId,
            porcentaje = porcentaje,
            aprobado = aprobado && quiz.modo == "oficial", // olo oficial puede aprobar
            preguntasIds = quiz.preguntas.map { it.preguntaId },
            modo = quiz.modo
        )
        
        //  Actualizar porcentaje del curso SOLO si se aprobó en modo oficial
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
                    println("Porcentaje del curso actualizado")
                }
            } catch (e: Exception) {
                println(" Error actualizando porcentaje: ${e.message}")
            }
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

    //  Evaluar respuestas y restar vidas EN TIEMPO REAL
    private suspend fun evaluarRespuestas(
        quiz: Quiz,
        respuestasUsuario: List<RespuestaUsuario>,
        userId: String
    ): Triple<List<RespuestaQuiz>, Int, Int> {

        var vidasActuales = quiz.vidasIniciales ?: run {
            val progreso = ServicioProgreso.obtenerProgreso(userId, quiz.cursoId)
                ?: throw IllegalStateException("Progreso no encontrado")
            (progreso["vidas"] as? Number)?.toInt() ?: VIDAS_MAX
        }

        val vidasIniciales = vidasActuales
        val respuestasEvaluadas = mutableListOf<RespuestaQuiz>()

        println(" Evaluando quiz ${quiz.id} - Modo: ${quiz.modo} - Vidas iniciales: $vidasActuales")

        for ((index, respuesta) in respuestasUsuario.withIndex()) {
            val pregunta = preguntaRepo.obtenerPreguntaPorId(respuesta.preguntaId)
                ?: throw IllegalStateException("Pregunta no encontrada")

            val opcionCorrecta = pregunta.opciones.indexOfFirst { it.esCorrecta }
            val esCorrecta = respuesta.respuestaSeleccionada == opcionCorrecta

            //  Restar vida INMEDIATAMENTE cuando falla
            if (!esCorrecta) {
                vidasActuales -= 1
                
                println(" Pregunta ${index + 1} incorrecta - Vidas: $vidasActuales/$VIDAS_MAX - Modo: ${quiz.modo}")

                // Actualizar vidas en Firebase EN TIEMPO REAL
                try {
                    ServicioProgreso.actualizarVidasInmediato(userId, quiz.cursoId, vidasActuales)
                    println(" Vidas actualizadas en Firebase: $vidasActuales")
                } catch (ex: Exception) {
                    println(" Error actualizando vidas en Firebase: ${ex.message}")
                }

                // Si se agotan las vidas, detener el quiz INMEDIATAMENTE
                if (vidasActuales <= 0) {
                    println(" SIN VIDAS - Quiz abandonado")
                    
                    val quizAbandonado = quiz.copy(
                        estado = "abandonado",
                        fin = Instant.now().toString(),
                        vidasFinales = 0,
                        respuestas = respuestasEvaluadas,
                        preguntasCorrectas = respuestasEvaluadas.count { it.esCorrecta },
                        preguntasIncorrectas = respuestasEvaluadas.count { !it.esCorrecta }
                    )
                    
                    try {
                        quizRepo.actualizarQuiz(quizAbandonado)
                        println("Quiz abandonado guardado en Firebase")
                    } catch (ex: Exception) {
                        println("Error guardando quiz abandonado: ${ex.message}")
                    }

                    throw IllegalStateException(
                        " Te has quedado sin vidas. El quiz se ha detenido.\n" +
                        " Respondiste correctamente ${respuestasEvaluadas.count { it.esCorrecta }} de ${respuestasEvaluadas.size} preguntas.\n" +
                        " Las vidas se recuperan cada $VIDA_REGEN_MINUTOS minutos."
                    )
                }
            } else {
                println(" Pregunta ${index + 1} correcta - Vidas: $vidasActuales/$VIDAS_MAX")
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

        val vidasPerdidas = vidasIniciales - vidasActuales
        println("Evaluación completa - Vidas perdidas: $vidasPerdidas - Vidas finales: $vidasActuales")

        return Triple(respuestasEvaluadas, vidasActuales, vidasPerdidas)
    }

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

        val progreso = ServicioProgreso.obtenerProgreso(userId, cursoId)
            ?: throw IllegalStateException("Progreso no encontrado")

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
            val perfilCurso = quizRepo.obtenerPerfilCurso(estudianteId, cursoId)
            if (perfilCurso != null) {
                val estadoActual = perfilCurso.temasCompletados[temaId]
                val ahora = System.currentTimeMillis()
                
                // Ambos modos incrementan quizzesRealizados
                val quizzesRealizados = (estadoActual?.quizzesRealizados ?: 0) + 1
                
                // Calcular promedio solo si hay quizzes previos
                val porcentajePromedio = if (estadoActual == null) porcentaje else {
                    ((estadoActual.porcentajePromedio * (quizzesRealizados - 1)) + porcentaje) / quizzesRealizados
                }
                
                val preguntasVistasActual = (estadoActual?.preguntasVistas ?: emptySet()) + preguntasIds.toSet()

                //  Solo marcar como aprobado si es modo oficial Y aprobó
                val temaAprobado = aprobado || (estadoActual?.aprobado ?: false)

                val nuevoEstado = EstadoTema(
                    temaId = temaId,
                    quizzesRealizados = quizzesRealizados,
                    quizzesRequeridos = estadoActual?.quizzesRequeridos ?: 0,
                    porcentajePromedio = porcentajePromedio,
                    aprobado = temaAprobado,
                    fechaPrimerIntento = estadoActual?.fechaPrimerIntento ?: ahora,
                    fechaUltimoIntento = ahora,
                    preguntasVistas = preguntasVistasActual
                )

                quizRepo.actualizarEstadoTema(estudianteId, cursoId, temaId, nuevoEstado)
                
                println("  Estado del tema actualizado: $temaId")
                println("   Quizzes realizados: $quizzesRealizados")
                println("   Modo: $modo")
                println("   Aprobado: $temaAprobado")
                println("   Porcentaje promedio: $porcentajePromedio%")
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