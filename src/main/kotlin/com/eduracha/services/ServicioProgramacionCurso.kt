package com.eduracha.services

import com.eduracha.models.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

object ServicioProgramacionCurso {

    // Configuración base
    const val QUIZZES_MINIMOS_POR_TEMA = 3
    const val PREGUNTAS_POR_QUIZ = 10
    const val DIAS_ENTRE_QUIZZES = 1 // Mínimo 1 día entre quizzes del mismo tema
    const val MILLIS_POR_DIA = 24L * 60 * 60 * 1000L
    const val DURACION_MIN_DIAS = 1
    const val DURACION_MAX_DIAS = 365

    /** Reloj inyectable para pruebas (por defecto tiempo real). */
    var reloj: () -> Long = { System.currentTimeMillis() }

    fun validarDuracion(dias: Int) {
        require(dias in DURACION_MIN_DIAS..DURACION_MAX_DIAS) {
            "duracionDias debe estar entre $DURACION_MIN_DIAS y $DURACION_MAX_DIAS (recibido: $dias)"
        }
    }

    /** Cálculo canónico: fechaFin = fechaInicio + dias * 86_400_000. Todo en UTC epoch-millis. */
    fun calcularFechaFin(fechaInicio: Long, duracionDias: Int): Long {
        validarDuracion(duracionDias)
        require(fechaInicio > 0) { "fechaInicio debe ser epoch-millis > 0" }
        return fechaInicio + duracionDias * MILLIS_POR_DIA
    }

    /** true si ahora ∈ [inicio, fin]. Límite inclusivo: exactamente en fin sigue vigente. */
    fun estaVigente(fechaInicio: Long, fechaFin: Long, ahora: Long = reloj()): Boolean =
        ahora in fechaInicio..fechaFin

    //Genera la programación completa del curso al crearlo o actualizarlo
    // Distribuye equitativamente y reparte el resto (dias % temas) un día extra
    // a los primeros temas, para no perder días. Usa fechaFin canónica del curso.
    fun generarProgramacion(curso: Curso, fechaInicio: Long = System.currentTimeMillis()): ProgramacionCurso {
        val temas = curso.temas?.values?.toList() ?: return ProgramacionCurso()
        val totalTemas = temas.size

        if (totalTemas == 0 || curso.duracionDias <= 0) {
            throw IllegalArgumentException("El curso debe tener al menos 1 tema y duración > 0")
        }
        validarDuracion(curso.duracionDias)

        val inicio = if (fechaInicio > 0) fechaInicio else reloj()
        val fechaFinCurso = calcularFechaFin(inicio, curso.duracionDias)
        val fechaInicioCurso = Instant.ofEpochMilli(inicio)
        
        val temasOrdenados = mutableListOf<String>()
        val distribucion = mutableMapOf<String, RangoTema>()
        
        var fechaActualTema = fechaInicioCurso.toEpochMilli()

        // Reparto con resto: base + 1 día extra a los primeros (resto) temas.
        // Último tema se ancla a fechaFinCurso para evitar deriva por redondeo.
        val base = curso.duracionDias / totalTemas
        val resto = curso.duracionDias % totalTemas
        val diasBase = max(1, base)

        temas.forEachIndexed { index, tema ->
            val temaId = tema.id ?: "tema_$index"
            temasOrdenados.add(temaId)

            val diasEsteTema = if (base >= 1) base + (if (index < resto) 1 else 0) else diasBase
            // Calcular fechas del tema
            val fechaInicioTema = fechaActualTema
            val fechaFinTema = if (index == totalTemas - 1) fechaFinCurso
                else fechaActualTema + diasEsteTema * MILLIS_POR_DIA

            // Calcular quizzes requeridos para el tema
            val quizzesRequeridos = calcularQuizzesRequeridos(diasEsteTema)

            distribucion[temaId] = RangoTema(
                temaId = temaId,
                titulo = tema.titulo,
                fechaInicio = fechaInicioTema,
                fechaFin = fechaFinTema,
                quizzesRequeridos = quizzesRequeridos,
                diasAsignados = diasEsteTema
            )

            fechaActualTema = fechaFinTema
        }
        
        return ProgramacionCurso(
            temasOrdenados = temasOrdenados,
            distribucionTemporal = distribucion
        )
    }
    
     // Calcula cuántos quizzes debe hacer el estudiante por tema
     //Basado en la duración asignada al tema
    private fun calcularQuizzesRequeridos(diasAsignados: Int): Int {
        return when {
            diasAsignados <= 2 -> QUIZZES_MINIMOS_POR_TEMA // 3 quizzes
            diasAsignados <= 5 -> 5 // 5 quizzes
            diasAsignados <= 10 -> 7 // 7 quizzes
            else -> 10 // Máximo 10 quizzes por tema
        }
    }
    
     //Determina si el estudiante puede realizar un quiz del tema en este momento
   fun puedeRealizarQuiz(
    cursoId: String,
    temaId: String,
    estudianteId: String,
    programacion: ProgramacionCurso,
    estadoTema: EstadoTema?,
    modo: String = "oficial" 
): ValidacionQuiz {
    
    // MODO PRÁCTICA: Permitir sin restricciones temporales
    if (modo == "practica") {
        return ValidacionQuiz(true, "Puedes practicar este tema libremente")
    }
    
    // Las fechas de distribucionTemporal son solo una GUÍA de ritmo. Antes bloqueaban el quiz
    // ("Este tema estará disponible en N días", "El período ya finalizó", "Ya completaste todos
    // los quizzes", "Debes esperar 1 día") y el estudiante quedaba sin poder hacer nada aunque el
    // mapa de islas le mostrara el tema abierto. El orden de las islas lo controla la app
    // (se abre la siguiente al aprobar con 80%) y los intentos los limitan las vidas.
    if (estadoTema?.aprobado == true) {
        return ValidacionQuiz(true, "Tema aprobado: puedes repasarlo")
    }
    val rangoTema = programacion.distribucionTemporal[temaId]
    val ahora = reloj()
    val mensaje = when {
        rangoTema == null -> "Puedes realizar el quiz"
        ahora < rangoTema.fechaInicio -> "Vas adelantado: este tema estaba sugerido para más adelante"
        ahora > rangoTema.fechaFin -> "Vas un poco atrasado con este tema, ¡ponte al día!"
        else -> "Puedes realizar el quiz"
    }
    return ValidacionQuiz(true, mensaje)
}

     // Obtiene el tema activo actual según la fecha
    fun obtenerTemaActivo(programacion: ProgramacionCurso, ahora: Long = reloj()): RangoTema? {
        return programacion.distribucionTemporal.values.firstOrNull {
            ahora in it.fechaInicio..it.fechaFin
        }
    }
    
     //Obtiene estadísticas de progreso del estudiante en el curso
    
    fun calcularProgresoCurso(
        programacion: ProgramacionCurso,
        temasCompletados: Map<String, EstadoTema>
    ): ProgresoCurso {
        val totalQuizzesRequeridos = programacion.distribucionTemporal.values.sumOf { it.quizzesRequeridos }
        val quizzesRealizados = temasCompletados.values.sumOf { it.quizzesRealizados }
        val temasAprobados = temasCompletados.values.count { it.aprobado }
        
        val porcentajeProgreso = if (totalQuizzesRequeridos > 0) {
            (quizzesRealizados * 100) / totalQuizzesRequeridos
        } else 0
        
        return ProgresoCurso(
            totalTemas = programacion.temasOrdenados.size,
            temasAprobados = temasAprobados,
            totalQuizzesRequeridos = totalQuizzesRequeridos,
            quizzesRealizados = quizzesRealizados,
            porcentajeProgreso = porcentajeProgreso
        )
    }
}

@kotlinx.serialization.Serializable
data class ValidacionQuiz(
    val permitido: Boolean,
    val mensaje: String
)

@kotlinx.serialization.Serializable
data class ProgresoCurso(
    val totalTemas: Int = 0,
    val temasAprobados: Int = 0,
    val totalQuizzesRequeridos: Int = 0,
    val quizzesRealizados: Int = 0,
    val porcentajeProgreso: Int = 0
)