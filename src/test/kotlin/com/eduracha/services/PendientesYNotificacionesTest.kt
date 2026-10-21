package com.eduracha.services

import com.eduracha.models.Curso
import com.eduracha.models.Pregunta
import com.eduracha.models.SolicitudCurso
import com.eduracha.models.Tema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendientesYNotificacionesTest {

    private fun curso(id: String, docente: String, temas: Map<String, Tema> = emptyMap()) =
        Curso(id = id, titulo = "Curso $id", codigo = id.uppercase(), docenteId = docente, temas = temas)

    @Test fun cuentaSoloLosCursosDelDocente() {
        val temas = mapOf(
            "t1" to Tema(id = "t1", titulo = "A", explicacion = "texto", explicacionEstado = "pendiente"),
            "t2" to Tema(id = "t2", titulo = "B", explicacion = null),
            "t3" to Tema(id = "t3", titulo = "C", explicacion = "ok", explicacionEstado = "aprobada")
        )
        val cursos = listOf(curso("c1", "doc", temas), curso("c2", "otro", temas), curso("c3", "doc"))
        val preguntas = listOf(Pregunta(id = "p1", cursoId = "c1"), Pregunta(id = "p2", cursoId = "c1"), Pregunta(id = "p3", cursoId = "c2"))
        val solicitudes = listOf(SolicitudCurso(id = "s1", cursoId = "c1"), SolicitudCurso(id = "s2", cursoId = "c2"))

        val r = CalculoPendientes.calcular("doc", cursos, preguntas, solicitudes)

        assertEquals(2, r.preguntas)
        assertEquals(1, r.explicaciones)
        assertEquals(1, r.temasSinExplicacion)
        assertEquals(1, r.aspirantes)
        assertEquals(5, r.total)
        assertEquals(listOf("c1"), r.cursos.map { it.cursoId }) // c3 no tiene pendientes; c2 es de otro docente
    }

    @Test fun docenteAlDia() {
        val r = CalculoPendientes.calcular("doc", listOf(curso("c1", "doc")), emptyList(), emptyList())
        assertEquals(0, r.total)
        assertTrue(r.cursos.isEmpty())
    }

    @Test fun documentoDeNotificacionSinNulosYCampos() {
        val d = Notificador.documento("Título", "Mensaje", "solicitud", mapOf("cursoId" to "c1", "temaId" to null, "vacio" to ""), ahora = 123L)
        assertEquals("Título", d["titulo"])
        assertEquals(false, d["leido"])
        assertEquals(123L, d["fecha"])
        assertEquals("c1", d["cursoId"])
        assertFalse(d.containsKey("temaId"))
        assertFalse(d.containsKey("vacio"))
    }
}
