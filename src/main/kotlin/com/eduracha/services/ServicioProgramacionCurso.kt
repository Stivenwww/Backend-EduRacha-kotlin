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
    
    /**
     * Genera la programación completa del curso al crearlo o actualizarlo
     */
    fun generarProgramacion(curso: Curso, fechaInicio: Long = System.currentTimeMillis()): ProgramacionCurso {
        val temas = curso.temas?.values?.toList() ?: return ProgramacionCurso()
        val totalTemas = temas.size
        
        if (totalTemas == 0 || curso.duracionDias <= 0) {
            throw IllegalArgumentException("El curso debe tener al menos 1 tema y duración > 0")
        }

        // Calcular días por tema (distribución equitativa)
        val diasPorTema = max(1, curso.duracionDias / totalTemas)
        val fechaInicioCurso = Instant.ofEpochMilli(fechaInicio)
        
        val temasOrdenados = mutableListOf<String>()
        val distribucion = mutableMapOf<String, RangoTema>()
        
        var fechaActualTema = fechaInicioCurso.toEpochMilli()
        
        temas.forEachIndexed { index, tema ->
            val temaId = tema.id ?: "tema_$index"
            temasOrdenados.add(temaId)
            
            // Calcular fechas del tema
            val fechaInicioTema = fechaActualTema
            val fechaFinTema = fechaActualTema + (diasPorTema * 24 * 60 * 60 * 1000L)
            
            // Calcular quizzes requeridos para el tema
            val quizzesRequeridos = calcularQuizzesRequeridos(diasPorTema)
            
            distribucion[temaId] = RangoTema(
                temaId = temaId,
                titulo = tema.titulo,
                fechaInicio = fechaInicioTema,
                fechaFin = fechaFinTema,
                quizzesRequeridos = quizzesRequeridos,
                diasAsignados = diasPorTema
            )
            
            fechaActualTema = fechaFinTema
        }
        
        return ProgramacionCurso(
            temasOrdenados = temasOrdenados,
            distribucionTemporal = distribucion
        )
    }
    
    /**
     * Calcula cuántos quizzes debe hacer el estudiante por tema
     * Basado en la duración asignada al tema
     */
    private fun calcularQuizzesRequeridos(diasAsignados: Int): Int {
        return when {
            diasAsignados <= 2 -> QUIZZES_MINIMOS_POR_TEMA // 3 quizzes
            diasAsignados <= 5 -> 5 // 5 quizzes
            diasAsignados <= 10 -> 7 // 7 quizzes
            else -> 10 // Máximo 10 quizzes por tema
        }
    }
    
    /**
     * Determina si el estudiante puede realizar un quiz del tema en este momento
     */
    fun puedeRealizarQuiz(
        cursoId: String,
        temaId: String,
        estudianteId: String,
        programacion: ProgramacionCurso,
        estadoTema: EstadoTema?
    ): ValidacionQuiz {
        val rangoTema = programacion.distribucionTemporal[temaId]
            ?: return ValidacionQuiz(false, "Tema no encontrado en la programación")
        
        val ahora = System.currentTimeMillis()
        
        // 1. Validar si el tema ya está disponible
        if (ahora < rangoTema.fechaInicio) {
            val diasRestantes = ((rangoTema.fechaInicio - ahora) / (1000 * 60 * 60 * 24)).toInt()
            return ValidacionQuiz(false, "Este tema estará disponible en $diasRestantes días")
        }
        
        // 2. Validar si el tema ya expiró
        if (ahora > rangoTema.fechaFin) {
            return ValidacionQuiz(false, "El período de este tema ya finalizó")
        }
        
        // 3. Validar si ya completó todos los quizzes requeridos
        val quizzesRealizados = estadoTema?.quizzesRealizados ?: 0
        if (quizzesRealizados >= rangoTema.quizzesRequeridos) {
            return ValidacionQuiz(false, "Ya completaste todos los quizzes de este tema")
        }
        
        // 4. Validar tiempo mínimo entre quizzes
        if (estadoTema != null && estadoTema.fechaUltimoIntento > 0) {
            val tiempoDesdeUltimo = (ahora - estadoTema.fechaUltimoIntento) / (1000 * 60 * 60 * 24)
            if (tiempoDesdeUltimo < DIAS_ENTRE_QUIZZES) {
                return ValidacionQuiz(false, "Debes esperar al menos $DIAS_ENTRE_QUIZZES día(s) entre quizzes")
            }
        }
        
        return ValidacionQuiz(true, "Puedes realizar el quiz")
    }
    
    /**
     * Obtiene el tema activo actual según la fecha
     */
    fun obtenerTemaActivo(programacion: ProgramacionCurso): RangoTema? {
        val ahora = System.currentTimeMillis()
        return programacion.distribucionTemporal.values.firstOrNull { 
            ahora in it.fechaInicio..it.fechaFin 
        }
    }
    
    /**
     * Obtiene estadísticas de progreso del estudiante en el curso
     */
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