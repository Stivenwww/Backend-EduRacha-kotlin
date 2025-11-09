package com.eduracha.services

import com.eduracha.repository.CursoRepository
import com.eduracha.repository.QuizRepository
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ServicioReportesExcel(
    private val quizRepo: QuizRepository,
    private val cursoRepo: CursoRepository
) {

    // ================== ESTILOS ==================

    private fun crearEstiloEncabezado(workbook: XSSFWorkbook): XSSFCellStyle {
        val style = workbook.createCellStyle() as XSSFCellStyle
        style.fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
        style.fillPattern = FillPatternType.SOLID_FOREGROUND
        style.borderBottom = BorderStyle.THIN
        style.borderTop = BorderStyle.THIN
        style.borderLeft = BorderStyle.THIN
        style.borderRight = BorderStyle.THIN
        style.alignment = HorizontalAlignment.CENTER
        style.verticalAlignment = VerticalAlignment.CENTER

        val font = workbook.createFont()
        font.bold = true
        font.fontHeightInPoints = 11
        style.setFont(font)
        return style
    }

    private fun crearEstiloDatos(workbook: XSSFWorkbook): XSSFCellStyle {
        val style = workbook.createCellStyle() as XSSFCellStyle
        style.borderBottom = BorderStyle.THIN
        style.borderTop = BorderStyle.THIN
        style.borderLeft = BorderStyle.THIN
        style.borderRight = BorderStyle.THIN
        return style
    }

    private fun crearEstiloCorrecta(workbook: XSSFWorkbook): XSSFCellStyle {
        val style = workbook.createCellStyle() as XSSFCellStyle
        style.fillForegroundColor = IndexedColors.LIGHT_GREEN.index
        style.fillPattern = FillPatternType.SOLID_FOREGROUND
        style.borderBottom = BorderStyle.THIN
        style.borderTop = BorderStyle.THIN
        style.borderLeft = BorderStyle.THIN
        style.borderRight = BorderStyle.THIN
        style.alignment = HorizontalAlignment.CENTER
        style.verticalAlignment = VerticalAlignment.CENTER
        return style
    }

    private fun crearEstiloIncorrecta(workbook: XSSFWorkbook): XSSFCellStyle {
        val style = workbook.createCellStyle() as XSSFCellStyle
        style.fillForegroundColor = IndexedColors.LIGHT_ORANGE.index
        style.fillPattern = FillPatternType.SOLID_FOREGROUND
        style.borderBottom = BorderStyle.THIN
        style.borderTop = BorderStyle.THIN
        style.borderLeft = BorderStyle.THIN
        style.borderRight = BorderStyle.THIN
        style.alignment = HorizontalAlignment.CENTER
        style.verticalAlignment = VerticalAlignment.CENTER
        return style
    }

    // ================== REPORTES ==================

    /**
     * Reporte Diario por Curso y Fecha
     */
    suspend fun generarReporteDiario(cursoId: String, fecha: LocalDate): ByteArray {
        val workbook = XSSFWorkbook()
        val curso = cursoRepo.obtenerCursoPorId(cursoId)
            ?: throw IllegalStateException("Curso no encontrado")

        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
        val sheet = workbook.createSheet("Reporte ${fecha.format(DateTimeFormatter.ISO_DATE)}")

        val headerStyle = crearEstiloEncabezado(workbook)
        val dataStyle = crearEstiloDatos(workbook)

        var rowNum = 0
        val titleRow = sheet.createRow(rowNum++)
        titleRow.createCell(0).apply {
            setCellValue("Reporte Diario - ${curso.titulo}")
            cellStyle = headerStyle
        }

        rowNum++

        val headers = listOf(
            "Estudiante ID", "Nombre", "Quizzes Realizados",
            "Promedio Correctas", "Experiencia Ganada"
        )

        val headerRow = sheet.createRow(rowNum++)
        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).apply {
                setCellValue(header)
                cellStyle = headerStyle
            }
        }

        estudiantes.forEach { estudiante ->
            val dataRow = sheet.createRow(rowNum++)
            val estudianteId = estudiante["userId"] as? String ?: ""
            val nombre = estudiante["nombre"] as? String ?: "N/A"

            val quizzes = quizRepo.obtenerQuizzesPorEstudiante(estudianteId, cursoId)
                .filter {
                    val quizDate = LocalDate.parse(it.inicio.substring(0, 10))
                    quizDate == fecha
                }

            val totalQuizzes = quizzes.size
            val promedioCorrectas = if (quizzes.isNotEmpty()) {
                quizzes.map { it.preguntasCorrectas }.average()
            } else 0.0
            val expTotal = quizzes.sumOf { it.experienciaGanada }

            dataRow.createCell(0).apply { setCellValue(estudianteId); cellStyle = dataStyle }
            dataRow.createCell(1).apply { setCellValue(nombre); cellStyle = dataStyle }
            dataRow.createCell(2).apply { setCellValue(totalQuizzes.toDouble()); cellStyle = dataStyle }
            dataRow.createCell(3).apply { setCellValue(promedioCorrectas); cellStyle = dataStyle }
            dataRow.createCell(4).apply { setCellValue(expTotal.toDouble()); cellStyle = dataStyle }
        }

        for (i in headers.indices) sheet.autoSizeColumn(i)

        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()
        return outputStream.toByteArray()
    }

    /**
     * Reporte de desempeño por tema
     */
    suspend fun generarReportePorTema(cursoId: String, temaId: String): ByteArray {
        val workbook = XSSFWorkbook()
        val curso = cursoRepo.obtenerCursoPorId(cursoId)
            ?: throw IllegalStateException("Curso no encontrado")

        val tema = cursoRepo.obtenerTema(cursoId, temaId)
            ?: throw IllegalStateException("Tema no encontrado")

        val sheet = workbook.createSheet("Reporte - ${tema.titulo}")

        val headerStyle = crearEstiloEncabezado(workbook)
        val correctaStyle = crearEstiloCorrecta(workbook)
        val incorrectaStyle = crearEstiloIncorrecta(workbook)
        val dataStyle = crearEstiloDatos(workbook) // ✅ agregado aquí

        var rowNum = 0
        val titleRow = sheet.createRow(rowNum++)
        titleRow.createCell(0).apply {
            setCellValue("Reporte por Tema - ${tema.titulo}")
            cellStyle = headerStyle
        }

        rowNum++

        val headers = listOf(
            "Estudiante", "Intentos", "Mejor Puntaje",
            "Promedio Tiempo (seg)", "Estado"
        )

        val headerRow = sheet.createRow(rowNum++)
        headers.forEachIndexed { index, header ->
            headerRow.createCell(index).apply {
                setCellValue(header)
                cellStyle = headerStyle
            }
        }

        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)

        estudiantes.forEach { estudiante ->
            val estudianteId = estudiante["userId"] as? String ?: ""
            val nombre = estudiante["nombre"] as? String ?: "N/A"

            val quizzes = quizRepo.obtenerQuizzesPorEstudiante(estudianteId, cursoId)
                .filter { it.temaId == temaId && it.estado == "finalizado" }

            if (quizzes.isNotEmpty()) {
                val dataRow = sheet.createRow(rowNum++)

                val intentos = quizzes.size
                val mejorPuntaje = quizzes.maxOfOrNull { it.preguntasCorrectas } ?: 0
                val promedioTiempo = quizzes.map { it.tiempoUsadoSeg }.average()
                val aprobado = mejorPuntaje >= 7

                dataRow.createCell(0).apply { setCellValue(nombre); cellStyle = dataStyle }
                dataRow.createCell(1).apply { setCellValue(intentos.toDouble()); cellStyle = dataStyle }
                dataRow.createCell(2).apply { setCellValue(mejorPuntaje.toDouble()); cellStyle = dataStyle }
                dataRow.createCell(3).apply { setCellValue(promedioTiempo); cellStyle = dataStyle }
                dataRow.createCell(4).apply {
                    setCellValue(if (aprobado) "Aprobado" else "En progreso")
                    cellStyle = if (aprobado) correctaStyle else incorrectaStyle
                }
            }
        }

        for (i in headers.indices) sheet.autoSizeColumn(i)

        val outputStream = ByteArrayOutputStream()
        workbook.write(outputStream)
        workbook.close()
        return outputStream.toByteArray()
    }
}
