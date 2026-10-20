package com.eduracha.services

import com.eduracha.models.Curso

/**
 * Normaliza fechas de vigencia del curso en el servidor.
 * Semántica conservada: duracionDias = días naturales de vigencia.
 * fechaFin = fechaInicio + duracionDias * 86_400_000 (UTC epoch-millis).
 * La programación siempre se regenera para evitar ventanas residuales.
 */
object CursoVigencia {

    fun normalizarParaCrear(curso: Curso, ahora: Long = System.currentTimeMillis()): Curso {
        ServicioProgramacionCurso.validarDuracion(curso.duracionDias)
        val inicio = if (curso.fechaInicio > 0) curso.fechaInicio else ahora
        val fin = ServicioProgramacionCurso.calcularFechaFin(inicio, curso.duracionDias)
        val base = curso.copy(fechaInicio = inicio, fechaFin = fin)
        // Solo genera programación si hay temas; si no, se generará al agregar temas.
        val prog = try {
            if (!base.temas.isNullOrEmpty()) ServicioProgramacionCurso.generarProgramacion(base, inicio)
            else base.programacion
        } catch (_: Exception) { base.programacion }
        return base.copy(programacion = prog)
    }

    fun normalizarParaActualizar(id: String, existente: Curso?, entrante: Curso): Curso {
        val duracion = if (entrante.duracionDias > 0) entrante.duracionDias
            else existente?.duracionDias ?: 0
        ServicioProgramacionCurso.validarDuracion(duracion)
        // Si el cliente envía fechaInicio > 0 se respeta (permite inicio futuro).
        // Si envía 0 se conserva la existente, o ahora como último recurso.
        val inicio = when {
            entrante.fechaInicio > 0 -> entrante.fechaInicio
            (existente?.fechaInicio ?: 0) > 0 -> existente!!.fechaInicio
            else -> System.currentTimeMillis()
        }
        val fin = ServicioProgramacionCurso.calcularFechaFin(inicio, duracion)
        val temas = entrante.temas ?: existente?.temas
        val base = entrante.copy(id = id, duracionDias = duracion, fechaInicio = inicio, fechaFin = fin, temas = temas)
        val prog = try {
            if (!temas.isNullOrEmpty()) ServicioProgramacionCurso.generarProgramacion(base, inicio)
            else entrante.programacion ?: existente?.programacion
        } catch (_: Exception) { entrante.programacion ?: existente?.programacion }
        return base.copy(programacion = prog)
    }
}
