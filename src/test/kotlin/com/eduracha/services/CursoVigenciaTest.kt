package com.eduracha.services

import com.eduracha.models.Curso
import com.eduracha.models.Tema
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CursoVigenciaTest {

    private fun curso(dias: Int, nTemas: Int = 3, inicio: Long = 1_700_000_000_000L): Curso {
        val temas = (0 until nTemas).associate { i -> "t$i" to Tema(id = "t$i", titulo = "Tema $i") }
        return Curso(titulo = "T", duracionDias = dias, fechaInicio = inicio, temas = temas)
    }

    @Test fun duraciones124730y90() {
        listOf(1, 2, 7, 30, 90).forEach { dias ->
            val inicio = 1_700_000_000_000L
            val fin = ServicioProgramacionCurso.calcularFechaFin(inicio, dias)
            assertEquals(inicio + dias * 86_400_000L, fin, "dias=$dias")
            assertTrue(ServicioProgramacionCurso.estaVigente(inicio, fin, inicio), "inicio vigente $dias")
            assertTrue(ServicioProgramacionCurso.estaVigente(inicio, fin, fin), "limite inclusivo $dias")
            assertFalse(ServicioProgramacionCurso.estaVigente(inicio, fin, fin + 1), "vencido $dias")
            assertFalse(ServicioProgramacionCurso.estaVigente(inicio, fin, inicio - 1), "antes de inicio $dias")
        }
    }

    @Test fun repartoNoPierdeDias() {
        // 30 días / 7 temas = base 4 + resto 2 → total debe seguir siendo 30 días
        val c = curso(30, 7)
        val prog = ServicioProgramacionCurso.generarProgramacion(c, c.fechaInicio)
        val total = prog.distribucionTemporal.values.sumOf { it.diasAsignados }
        assertEquals(30, total)
        val ultimo = prog.distribucionTemporal.values.maxByOrNull { it.fechaFin }!!
        assertEquals(c.fechaInicio + 30 * 86_400_000L, ultimo.fechaFin)
    }

    @Test fun duracionInvalidaRechazada() {
        assertFailsWith<IllegalArgumentException> { ServicioProgramacionCurso.validarDuracion(0) }
        assertFailsWith<IllegalArgumentException> { ServicioProgramacionCurso.validarDuracion(366) }
    }

    @Test fun normalizarCrearRespetaInicioFuturo() {
        val futuro = 2_000_000_000_000L
        val out = CursoVigencia.normalizarParaCrear(curso(30, 2, futuro), ahora = 1_700_000_000_000L)
        assertEquals(futuro, out.fechaInicio)
        assertEquals(futuro + 30 * 86_400_000L, out.fechaFin)
    }

    @Test fun normalizarActualizarConservaInicioSiClienteEnviaCero() {
        val existente = curso(30, 2, 1_700_000_000_000L).copy(fechaFin = 1_700_000_000_000L + 30 * 86_400_000L)
        val entrante = curso(60, 2, 0)
        val out = CursoVigencia.normalizarParaActualizar("id1", existente, entrante)
        assertEquals(1_700_000_000_000L, out.fechaInicio)
        assertEquals(1_700_000_000_000L + 60 * 86_400_000L, out.fechaFin)
    }

    @Test fun medianocheYBogotaConsistentes() {
        // Medianoche Bogotá del 2026-01-01 en UTC millis
        val bogota = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneId.of("America/Bogota")).toInstant().toEpochMilli()
        val fin = ServicioProgramacionCurso.calcularFechaFin(bogota, 1)
        assertEquals(bogota + 86_400_000L, fin)
        assertTrue(ServicioProgramacionCurso.estaVigente(bogota, fin, bogota + 86_400_000L - 1))
        assertTrue(ServicioProgramacionCurso.estaVigente(bogota, fin, fin))
        assertFalse(ServicioProgramacionCurso.estaVigente(bogota, fin, fin + 1))
    }
}
