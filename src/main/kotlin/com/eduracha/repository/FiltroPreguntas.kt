package com.eduracha.repository

import com.eduracha.models.Pregunta

/**
 * Contrato del filtro `?estado=` de GET /api/preguntas.
 * - `null`, vacío o "todos" (cualquier capitalización) = sin filtro.
 * - Otro valor = igualdad insensible a mayúsculas, con trim.
 * Se aplica con y sin `cursoId`.
 */
fun filtrarPreguntasPorEstado(preguntas: List<Pregunta>, estado: String?): List<Pregunta> {
    val e = estado?.trim()
    if (e.isNullOrEmpty() || e.equals("todos", ignoreCase = true)) return preguntas
    return preguntas.filter { it.estado.equals(e, ignoreCase = true) }
}
