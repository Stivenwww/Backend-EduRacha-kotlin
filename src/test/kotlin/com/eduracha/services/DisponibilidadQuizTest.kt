package com.eduracha.services

import com.eduracha.models.EstadoTema
import com.eduracha.models.ProgramacionCurso
import com.eduracha.models.RangoTema
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Regresión: "Este tema estará disponible en 79 días" dejaba al estudiante sin poder hacer quizzes. */
class DisponibilidadQuizTest {
    private val dia = 86_400_000L
    private val ahora = 1_800_000_000_000L
    private val prog = ProgramacionCurso(
        temasOrdenados = listOf("t1", "t2", "t3"),
        distribucionTemporal = mapOf(
            "t1" to RangoTema("t1", "Uno", ahora - 10 * dia, ahora - 5 * dia, 3, 5),
            "t2" to RangoTema("t2", "Dos", ahora - 5 * dia, ahora + 5 * dia, 5, 10),
            "t3" to RangoTema("t3", "Tres", ahora + 79 * dia, ahora + 90 * dia, 7, 11)
        )
    )

    @AfterTest fun restaurarReloj() { ServicioProgramacionCurso.reloj = { System.currentTimeMillis() } }

    private fun puede(tema: String, estado: EstadoTema? = null) =
        ServicioProgramacionCurso.puedeRealizarQuiz("c", tema, "u", prog, estado).permitido

    @Test fun temaFuturoNoBloquea() {
        ServicioProgramacionCurso.reloj = { ahora }
        assertTrue(puede("t3"))
    }

    @Test fun temaConPeriodoVencidoNoBloquea() {
        ServicioProgramacionCurso.reloj = { ahora }
        assertTrue(puede("t1"))
    }

    @Test fun sinEsperaEntreIntentosNiTopeDeQuizzes() {
        ServicioProgramacionCurso.reloj = { ahora }
        val estado = EstadoTema(temaId = "t2", quizzesRealizados = 9, fechaUltimoIntento = ahora - 60_000)
        assertTrue(puede("t2", estado))
    }

    @Test fun temaNuevoFueraDeLaProgramacionNoBloquea() {
        ServicioProgramacionCurso.reloj = { ahora }
        assertTrue(puede("tema_agregado_despues"))
        assertTrue(ServicioProgramacionCurso.puedeRealizarQuiz("c", "x", "u", ProgramacionCurso(), null).permitido)
    }
}
