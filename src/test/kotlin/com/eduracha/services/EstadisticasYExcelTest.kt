package com.eduracha.services

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EstadisticasYExcelTest {

    private val hoy = LocalDate.of(2026, 10, 20)
    private fun q(est: String, tema: String, dia: LocalDate, ok: Int, mal: Int, modo: String = "oficial",
                  resp: List<Pair<String, Boolean>> = emptyList()) =
        QuizResumen(est, tema, "${dia}T15:00:00Z", "finalizado", modo, ok, mal, 60, 10, resp)

    @Test fun calculaResumenPorTemaYRiesgo() {
        val estudiantes = listOf(
            EstudianteBase("a", "Ana", 300, 5, 5),
            EstudianteBase("b", "Beto", 100, 0, 0),
            EstudianteBase("c", "Caro", 0, 0, 5)
        )
        val r = listOf("p1" to false, "p2" to true)
        val quizzes = listOf(
            q("a", "t1", hoy, 9, 1, resp = r), q("a", "t2", hoy.minusDays(1), 8, 2, resp = r),
            q("b", "t1", hoy.minusDays(10), 3, 7, resp = r), q("b", "t1", hoy.minusDays(9), 4, 6),
            QuizResumen("a", "t1", "${hoy}T10:00:00Z", "en_progreso", "oficial", 0, 0, 0, 0),
            q("zzz", "t1", hoy, 10, 0) // estudiante que ya no está en el curso: se ignora
        )
        val e = CalculoEstadisticas.calcular("c1", "Curso", linkedMapOf("t1" to "Tema 1", "t2" to "Tema 2"),
            estudiantes, quizzes, mapOf("p1" to "¿Qué es X?"), hoy)

        assertEquals(3, e.resumen.totalEstudiantes)
        assertEquals(4, e.resumen.quizzesFinalizados)
        assertEquals(1, e.resumen.activosUltimos7Dias)
        assertEquals(50, e.resumen.tasaAprobacion)          // 2 de 4 con 80% o más
        assertEquals(14, e.actividadDiaria.size)
        assertEquals(1, e.actividadDiaria.last().quizzes)
        assertEquals(listOf("t1", "t2"), e.porTema.map { it.temaId })
        assertEquals(3, e.porTema[0].intentos)
        assertEquals(2, e.resumen.estudiantesEnRiesgo)      // Beto (inactivo y bajo) y Caro (sin quizzes)
        assertTrue(e.estudiantes.first { it.estudianteId == "c" }.motivoRiesgo!!.contains("quizzes"))
        assertEquals(2, e.estudiantes.first { it.estudianteId == "a" }.temasAprobados)
        assertEquals("p1", e.preguntasDificiles.first().preguntaId)
        assertEquals("¿Qué es X?", e.preguntasDificiles.first().texto)
        assertEquals(4, e.distribucionPuntajes.sumOf { it.cantidad })
    }

    @Test fun cursoSinDatosNoFalla() {
        val e = CalculoEstadisticas.calcular("c1", "Curso", emptyMap(), emptyList(), emptyList(), hoy = hoy)
        assertEquals(0, e.resumen.promedioAciertos)
        assertEquals(0.0, e.resumen.rachaPromedio)
    }

    @Test fun libroEduRachaTieneMarcaTablaYGrafico() {
        val bytes = LibroExcelEduRacha.crear(LibroExcelEduRacha.Reporte(
            titulo = "Reporte general del curso", curso = "Programación Básica", detalle = "Todo el historial",
            indicadores = listOf(LibroExcelEduRacha.Indicador("Estudiantes", "3"), LibroExcelEduRacha.Indicador("XP total", "1.250")),
            tituloTabla = "Ranking",
            columnas = listOf(
                LibroExcelEduRacha.Columna("Puesto", LibroExcelEduRacha.TipoCelda.ENTERO),
                LibroExcelEduRacha.Columna("Estudiante"),
                LibroExcelEduRacha.Columna("Promedio", LibroExcelEduRacha.TipoCelda.DECIMAL),
                LibroExcelEduRacha.Columna("XP", LibroExcelEduRacha.TipoCelda.ENTERO),
                LibroExcelEduRacha.Columna("Estado", LibroExcelEduRacha.TipoCelda.ESTADO)
            ),
            filas = listOf(
                listOf(1, "José Ñúñez", 8.5, 800, "Aprobado"),
                listOf(2, "Laura", "7,2", 300, "En progreso"),
                listOf(3, "Brayner", Double.NaN, 150, "Sin actividad")
            ),
            grafico = LibroExcelEduRacha.Grafico("XP por estudiante", 1, 3, "XP")
        ))
        System.getenv("EXCEL_MUESTRA")?.let { File(it).writeBytes(bytes) }
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { libro ->
            val hoja = libro.getSheetAt(0)
            assertTrue(hoja.getRow(0).getCell(0).stringCellValue.startsWith("EduRacha"))
            assertEquals("Reporte general del curso", hoja.getRow(1).getCell(0).stringCellValue)
            val textos = hoja.flatMap { r -> r.map { c -> c.toString() } }
            assertTrue("José Ñúñez" in textos)
            val fila = hoja.first { r -> r.getCell(1)?.toString() == "Laura" }
            assertEquals(7.2, fila.getCell(2).numericCellValue)
            assertEquals(1, hoja.drawingPatriarch.charts.size)
            assertTrue(hoja.ctWorksheet.isSetAutoFilter)
        }
    }
}
