package com.eduracha.repository

import com.eduracha.models.Pregunta
import kotlin.test.Test
import kotlin.test.assertEquals

class FiltroPreguntasTest {

    private fun p(id: String, estado: String) = Pregunta(id = id, estado = estado, texto = "t")

    private val base = listOf(
        p("1", "pendiente_revision"),
        p("2", "aprobada"),
        p("3", "rechazada"),
        p("4", "PENDIENTE_REVISION"),
        p("5", "")
    )

    @Test fun filtraPendientesSinCursoId() {
        val out = filtrarPreguntasPorEstado(base, "pendiente_revision")
        assertEquals(listOf("1", "4"), out.map { it.id })
    }

    @Test fun todosNoFiltra() {
        assertEquals(5, filtrarPreguntasPorEstado(base, "todos").size)
        assertEquals(5, filtrarPreguntasPorEstado(base, "TODOS").size)
        assertEquals(5, filtrarPreguntasPorEstado(base, null).size)
        assertEquals(5, filtrarPreguntasPorEstado(base, "  ").size)
    }

    @Test fun trimYCaseInsensitive() {
        val out = filtrarPreguntasPorEstado(base, "  Aprobada ")
        assertEquals(listOf("2"), out.map { it.id })
    }

    @Test fun estadoInexistenteDevuelveVacio() {
        assertEquals(0, filtrarPreguntasPorEstado(base, "borrador").size)
    }
}
