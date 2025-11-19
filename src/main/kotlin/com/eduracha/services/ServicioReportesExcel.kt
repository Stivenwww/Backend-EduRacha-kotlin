package com.eduracha.services

import com.eduracha.repository.CursoRepository
import com.eduracha.repository.QuizRepository
import com.eduracha.routes.*
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ServicioReportesExcel(
    private val quizRepo: QuizRepository,
    private val cursoRepo: CursoRepository
) {

    // ESTILOS EXCEL 
    
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

    
    private fun parsearFechaQuiz(fechaStr: String, zona: ZoneId = ZoneId.of("America/Bogota")): LocalDate? {
        return try {
            Instant.parse(fechaStr).atZone(zona).toLocalDate()
        } catch (e: Exception) {
            println(" Error parseando fecha $fechaStr → ${e.message}")
            null
        }
    }

    
    private data class DatosQuizSimple(
        val id: String,
        val inicio: String,
        val fin: String?,
        val estado: String,
        val temaId: String,
        val preguntasCorrectas: Int,
        val experienciaGanada: Int,
        val tiempoUsadoSeg: Int
    )

    /**
     * Obtiene quizzes directamente desde Firebase
     * Evita problemas de mapeo del modelo Quiz completo
     */
    private suspend fun obtenerQuizzesSimplificados(
        estudianteId: String,
        cursoId: String
    ): List<DatosQuizSimple> = suspendCancellableCoroutine { cont ->
        
        println(" [ServicioReportes] Obteniendo quizzes simplificados:")
        println("    Estudiante: $estudianteId")
        println("    Curso: $cursoId")
        
        val database = FirebaseDatabase.getInstance()
        val refQuizzes = database.getReference("quizzes")
        
        refQuizzes.orderByChild("estudianteId").equalTo(estudianteId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val quizzes = mutableListOf<DatosQuizSimple>()
                    
                    println("    Total quizzes del estudiante: ${snapshot.childrenCount}")
                    
                    for (quizSnap in snapshot.children) {
                        try {
                            val quizCursoId = quizSnap.child("cursoId").getValue(String::class.java)
                            
                            // Filtrar por curso
                            if (quizCursoId == cursoId) {
                                val id = quizSnap.key ?: ""
                                val inicio = quizSnap.child("inicio").getValue(String::class.java) ?: ""
                                val fin = quizSnap.child("fin").getValue(String::class.java)
                                val estado = quizSnap.child("estado").getValue(String::class.java) ?: "en_progreso"
                                val temaId = quizSnap.child("temaId").getValue(String::class.java) ?: ""
                                
                                val preguntasCorrectas = when (val value = quizSnap.child("preguntasCorrectas").value) {
                                    is Long -> value.toInt()
                                    is Int -> value
                                    is Double -> value.toInt()
                                    else -> 0
                                }
                                
                                val experienciaGanada = when (val value = quizSnap.child("experienciaGanada").value) {
                                    is Long -> value.toInt()
                                    is Int -> value
                                    is Double -> value.toInt()
                                    else -> 0
                                }
                                
                                val tiempoUsadoSeg = when (val value = quizSnap.child("tiempoUsadoSeg").value) {
                                    is Long -> value.toInt()
                                    is Int -> value
                                    is Double -> value.toInt()
                                    else -> 0
                                }
                                
                                val quiz = DatosQuizSimple(
                                    id = id,
                                    inicio = inicio,
                                    fin = fin,
                                    estado = estado,
                                    temaId = temaId,
                                    preguntasCorrectas = preguntasCorrectas,
                                    experienciaGanada = experienciaGanada,
                                    tiempoUsadoSeg = tiempoUsadoSeg
                                )
                                
                                println("       Quiz encontrado: $id")
                                println("         - Tema: $temaId")
                                println("         - Estado: $estado")
                                println("         - Correctas: $preguntasCorrectas")
                                println("         - XP: $experienciaGanada")
                                println("         - Tiempo: ${tiempoUsadoSeg}s")
                                
                                quizzes.add(quiz)
                            }
                        } catch (e: Exception) {
                            println("       Error procesando quiz ${quizSnap.key}: ${e.message}")
                        }
                    }
                    println("    Total quizzes retornados: ${quizzes.size}")
                    cont.resume(quizzes)
                }
                
                override fun onCancelled(error: DatabaseError) {
                    println("   Error Firebase: ${error.message}")
                    cont.resumeWithException(error.toException())
                }
            })
    }


    suspend fun obtenerDatosReporteDiarioSerializable(cursoId: String, fecha: LocalDate): DatosReporteDiarioResponse {
        println("\n REPORTE DIARIO ")
        println("Fecha: $fecha")
        println(" Curso: $cursoId")
        
        val curso = cursoRepo.obtenerCursoPorId(cursoId) ?: throw IllegalStateException("Curso no encontrado")
        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
        
        println("Total estudiantes en curso: ${estudiantes.size}")
        
        val datosEstudiantes = mutableListOf<EstudianteReporteData>()

        estudiantes.forEach { est ->
            val estudianteId = est["userId"] as? String ?: ""
            val nombre = est["nombre"] as? String ?: "N/A"
            
            println("\n    Procesando: $nombre ($estudianteId)")
            
            val todosQuizzes = obtenerQuizzesSimplificados(estudianteId, cursoId)
            println("       Total quizzes: ${todosQuizzes.size}")
            
            val quizzesDia = todosQuizzes.filter { quiz ->
                val fechaQuiz = parsearFechaQuiz(quiz.inicio)
                val coincide = fechaQuiz == fecha
                
                if (coincide) {
                    println("     Quiz del día: ${quiz.id} - Correctas: ${quiz.preguntasCorrectas}, XP: ${quiz.experienciaGanada}")
                }
                
                coincide
            }
            
            val total = quizzesDia.size
            val promedio = if (total > 0) quizzesDia.map { it.preguntasCorrectas }.average() else 0.0
            val xp = quizzesDia.sumOf { it.experienciaGanada }
            
            println("   Resultados: Total=$total, Promedio=$promedio, XP=$xp")

            datosEstudiantes.add(EstudianteReporteData(estudianteId, nombre, total, "%.1f".format(promedio), xp))
        }

        println("\nReporte completado: ${datosEstudiantes.count { it.quizzesRealizados > 0 }} estudiantes con actividad")
        println("=========================================\n")        
        return DatosReporteDiarioResponse(
            cursoId, 
            curso.titulo, 
            fecha.toString(), 
            estudiantes.size, 
            datosEstudiantes.count { it.quizzesRealizados > 0 }, 
            datosEstudiantes
        )
    }

    suspend fun obtenerDatosReporteTemaSerializable(cursoId: String, temaId: String): DatosReporteTemaResponse {
        println("\n REPORTE POR TEMA ")
        println("Curso: $cursoId")
        println(" Tema: $temaId")
        
        val curso = cursoRepo.obtenerCursoPorId(cursoId) ?: throw IllegalStateException("Curso no encontrado")
        val tema = cursoRepo.obtenerTema(cursoId, temaId) ?: throw IllegalStateException("Tema no encontrado")
        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
        
        val datosEstudiantes = mutableListOf<EstudianteTemaData>()

        estudiantes.forEach { est ->
            val estudianteId = est["userId"] as? String ?: ""
            val nombre = est["nombre"] as? String ?: "N/A"
            
            println("\n    Procesando: $nombre")
            
            val todosQuizzes = obtenerQuizzesSimplificados(estudianteId, cursoId)
            val quizzesTema = todosQuizzes.filter { it.temaId == temaId && it.estado == "finalizado" }
            
            println("     Quizzes del tema: ${quizzesTema.size}")

            if (quizzesTema.isNotEmpty()) {
                val intentos = quizzesTema.size
                val mejor = quizzesTema.maxOf { it.preguntasCorrectas }
                val promTiempo = quizzesTema.map { it.tiempoUsadoSeg }.average()
                val aprobado = mejor >= 7
                
                println(" Mejor: $mejor, Intentos: $intentos, Tiempo prom: $promTiempo")

                datosEstudiantes.add(EstudianteTemaData(
                    estudianteId, 
                    nombre, 
                    intentos, 
                    mejor, 
                    "%.1f".format(promTiempo), 
                    if (aprobado) "Aprobado" else "En progreso"
                ))
            }
        }

        println("\nReporte completado: ${datosEstudiantes.size} estudiantes con datos")
        println("=========================================\n")
        
        return DatosReporteTemaResponse(
            cursoId, 
            curso.titulo, 
            temaId, 
            tema.titulo, 
            estudiantes.size, 
            datosEstudiantes.size, 
            datosEstudiantes
        )
    }

    suspend fun obtenerDatosReporteGeneralSerializable(cursoId: String): DatosReporteGeneralResponse {
        println("\n========== REPORTE GENERAL ==========")
        println("Curso: $cursoId")
        
        val curso = cursoRepo.obtenerCursoPorId(cursoId) ?: throw IllegalStateException("Curso no encontrado")
        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
        val datosEstudiantes = mutableListOf<EstudianteGeneralData>()

        estudiantes.forEach { est ->
            val estudianteId = est["userId"] as? String ?: ""
            val nombre = est["nombre"] as? String ?: "N/A"
            
            val todosQuizzes = obtenerQuizzesSimplificados(estudianteId, cursoId)
            val quizzesFinalizados = todosQuizzes.filter { it.estado == "finalizado" }

            if (quizzesFinalizados.isNotEmpty()) {
                val totalQuizzes = quizzesFinalizados.size
                val promedioCorrectas = quizzesFinalizados.map { it.preguntasCorrectas }.average()
                val xpTotal = quizzesFinalizados.sumOf { it.experienciaGanada }
                val temasCompletados = quizzesFinalizados.map { it.temaId }.distinct().size
                
                datosEstudiantes.add(EstudianteGeneralData(
                    estudianteId, 
                    nombre, 
                    totalQuizzes, 
                    "%.1f".format(promedioCorrectas), 
                    xpTotal, 
                    temasCompletados
                ))
            }
        }

        println(" Reporte completado: ${datosEstudiantes.size} estudiantes")
        println("========================================\n")
        
        return DatosReporteGeneralResponse(
            cursoId, 
            curso.titulo, 
            estudiantes.size, 
            datosEstudiantes.size, 
            datosEstudiantes
        )
    }

    suspend fun obtenerDatosReporteRangoSerializable(cursoId: String, desde: LocalDate, hasta: LocalDate): DatosReporteRangoResponse {
        println("\n========== REPORTE POR RANGO ==========")
        println("Curso: $cursoId")
        println(" Rango: $desde a $hasta")
        
        val curso = cursoRepo.obtenerCursoPorId(cursoId) ?: throw IllegalStateException("Curso no encontrado")
        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
        val datosEstudiantes = mutableListOf<EstudianteRangoData>()

        estudiantes.forEach { est ->
            val estudianteId = est["userId"] as? String ?: ""
            val nombre = est["nombre"] as? String ?: "N/A"
            
            val todosQuizzes = obtenerQuizzesSimplificados(estudianteId, cursoId)
            val quizzesRango = todosQuizzes.filter { quiz ->
                val fechaQuiz = parsearFechaQuiz(quiz.inicio)
                fechaQuiz != null && !fechaQuiz.isBefore(desde) && !fechaQuiz.isAfter(hasta)
            }

            if (quizzesRango.isNotEmpty()) {
                val totalQuizzes = quizzesRango.size
                val promedioCorrectas = quizzesRango.map { it.preguntasCorrectas }.average()
                val xpTotal = quizzesRango.sumOf { it.experienciaGanada }
                val tiempoPromedio = quizzesRango.map { it.tiempoUsadoSeg }.average()
                
                datosEstudiantes.add(EstudianteRangoData(
                    estudianteId, 
                    nombre, 
                    totalQuizzes, 
                    "%.1f".format(promedioCorrectas), 
                    xpTotal, 
                    "%.1f".format(tiempoPromedio)
                ))
            }
        }

        println("Reporte completado: ${datosEstudiantes.size} estudiantes")
        println("=========================================\n")
        
        return DatosReporteRangoResponse(
            cursoId, 
            curso.titulo, 
            desde.toString(), 
            hasta.toString(), 
            estudiantes.size, 
            datosEstudiantes.size, 
            datosEstudiantes
        )
    }

    // MÉTODOS PARA GENERAR ARCHIVOS EXCEL

    suspend fun generarReporteDiario(cursoId: String, fecha: LocalDate): Pair<ByteArray, String> {
        val workbook = XSSFWorkbook()
        val curso = cursoRepo.obtenerCursoPorId(cursoId) ?: throw IllegalStateException("Curso no encontrado")
        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
        val sheet = workbook.createSheet("Reporte-${fecha}")
        val headerStyle = crearEstiloEncabezado(workbook)
        val dataStyle = crearEstiloDatos(workbook)
        var rowNum = 0

        sheet.createRow(rowNum++).createCell(0).apply { setCellValue("Reporte Diario - ${curso.titulo}"); cellStyle = headerStyle }
        rowNum++

        val headers = listOf("Estudiante ID", "Nombre", "Quizzes Realizados", "Promedio Correctas", "Experiencia Ganada")
        val headerRow = sheet.createRow(rowNum++)
        headers.forEachIndexed { i, h -> headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle } }

        estudiantes.forEach { est ->
            val estudianteId = est["userId"] as? String ?: ""
            val nombre = est["nombre"] as? String ?: "N/A"
            
            val todosQuizzes = obtenerQuizzesSimplificados(estudianteId, cursoId)
            val quizzesDia = todosQuizzes.filter { parsearFechaQuiz(it.inicio) == fecha }
            
            val total = quizzesDia.size
            val promedio = if (total > 0) quizzesDia.map { it.preguntasCorrectas }.average() else 0.0
            val xp = quizzesDia.sumOf { it.experienciaGanada }

            val row = sheet.createRow(rowNum++)
            row.createCell(0).apply { setCellValue(estudianteId); cellStyle = dataStyle }
            row.createCell(1).apply { setCellValue(nombre); cellStyle = dataStyle }
            row.createCell(2).apply { setCellValue(total.toDouble()); cellStyle = dataStyle }
            row.createCell(3).apply { setCellValue("%.1f".format(promedio)); cellStyle = dataStyle }
            row.createCell(4).apply { setCellValue(xp.toDouble()); cellStyle = dataStyle }

            try {
                guardarResumenDiarioUsuario(estudianteId, fecha, mapOf(
                    "cursoId" to cursoId, 
                    "cursoNombre" to curso.titulo, 
                    "fecha" to fecha.toString(), 
                    "quizzesRealizados" to total, 
                    "promedioCorrectas" to "%.1f".format(promedio), 
                    "experienciaGanada" to xp
                ))
            } catch (e: Exception) {
                println(" Error guardando resumen para $estudianteId: ${e.message}")
            }
        }

        headers.indices.forEach { sheet.autoSizeColumn(it) }
        val out = ByteArrayOutputStream()
        workbook.write(out)
        workbook.close()

        return Pair(out.toByteArray(), "reporte_diario_${curso.titulo.replace(" ", "_")}_${fecha}.xlsx")
    }

    private suspend fun guardarResumenDiarioUsuario(uid: String, fecha: LocalDate, resumen: Map<String, Any>) = suspendCancellableCoroutine<Unit> { cont ->
        try {
            val fechaStr = fecha.format(DateTimeFormatter.ISO_DATE)
            val ref = FirebaseDatabase.getInstance().getReference("usuarios").child(uid).child("actividad_diaria").child(fechaStr)
            ref.setValue(resumen) { error, _ -> 
                if (error != null) {
                    println(" Error guardando actividad diaria: ${error.message}")
                    cont.resumeWithException(error.toException())
                } else {
                    println(" Actividad diaria guardada: $uid - $fechaStr")
                    cont.resume(Unit)
                }
            }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    suspend fun generarReportePorTema(cursoId: String, temaId: String): Pair<ByteArray, String> {
        val workbook = XSSFWorkbook()
        val curso = cursoRepo.obtenerCursoPorId(cursoId) ?: throw IllegalStateException("Curso no encontrado")
        val tema = cursoRepo.obtenerTema(cursoId, temaId) ?: throw IllegalStateException("Tema no encontrado")
        val sheet = workbook.createSheet("Reporte-${tema.titulo}")
        val headerStyle = crearEstiloEncabezado(workbook)
        val correctaStyle = crearEstiloCorrecta(workbook)
        val incorrectaStyle = crearEstiloIncorrecta(workbook)
        val dataStyle = crearEstiloDatos(workbook)
        var rowNum = 0

        sheet.createRow(rowNum++).createCell(0).apply { setCellValue("Reporte por Tema - ${tema.titulo}"); cellStyle = headerStyle }
        rowNum++

        val headers = listOf("Estudiante", "Intentos", "Mejor Puntaje", "Promedio Tiempo (seg)", "Estado")
        val headerRow = sheet.createRow(rowNum++)
        headers.forEachIndexed { i, h -> headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle } }

        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
        estudiantes.forEach { est ->
            val estudianteId = est["userId"] as? String ?: ""
            val nombre = est["nombre"] as? String ?: "N/A"
            
            val todosQuizzes = obtenerQuizzesSimplificados(estudianteId, cursoId)
            val quizzesTema = todosQuizzes.filter { it.temaId == temaId && it.estado == "finalizado" }

            if (quizzesTema.isNotEmpty()) {
                val intentos = quizzesTema.size
                val mejor = quizzesTema.maxOf { it.preguntasCorrectas }
                val promTiempo = quizzesTema.map { it.tiempoUsadoSeg }.average()
                val aprobado = mejor >= 7

                val row = sheet.createRow(rowNum++)
                row.createCell(0).apply { setCellValue(nombre); cellStyle = dataStyle }
                row.createCell(1).apply { setCellValue(intentos.toDouble()); cellStyle = dataStyle }
                row.createCell(2).apply { setCellValue(mejor.toDouble()); cellStyle = dataStyle }
                row.createCell(3).apply { setCellValue("%.1f".format(promTiempo)); cellStyle = dataStyle }
                row.createCell(4).apply { setCellValue(if (aprobado) "Aprobado" else "En progreso"); cellStyle = if (aprobado) correctaStyle else incorrectaStyle }
            }
        }

        headers.indices.forEach { sheet.autoSizeColumn(it) }
        val out = ByteArrayOutputStream()
        workbook.write(out)
        workbook.close()

        return Pair(out.toByteArray(), "reporte_tema_${tema.titulo.replace(" ", "_")}.xlsx")
    }

    suspend fun generarReporteGeneralCurso(cursoId: String): Pair<ByteArray, String> {
        val workbook = XSSFWorkbook()
        val curso = cursoRepo.obtenerCursoPorId(cursoId) ?: throw IllegalStateException("Curso no encontrado")
        val sheet = workbook.createSheet("Reporte General")
        val headerStyle = crearEstiloEncabezado(workbook)
        val dataStyle = crearEstiloDatos(workbook)
        var rowNum = 0

        sheet.createRow(rowNum++).createCell(0).apply { setCellValue("Reporte General - ${curso.titulo}"); cellStyle = headerStyle }
        rowNum++

        val headers = listOf("Estudiante", "Total Quizzes", "Promedio General", "Experiencia Total", "Temas Completados")
        val headerRow = sheet.createRow(rowNum++)
        headers.forEachIndexed { i, h -> headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle } }

        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
        estudiantes.forEach { est ->
            val estudianteId = est["userId"] as? String ?: ""
            val nombre = est["nombre"] as? String ?: "N/A"
            
            val todosQuizzes = obtenerQuizzesSimplificados(estudianteId, cursoId)
            val quizzesFinalizados = todosQuizzes.filter { it.estado == "finalizado" }

            if (quizzesFinalizados.isNotEmpty()) {
                val row = sheet.createRow(rowNum++)
                row.createCell(0).apply { setCellValue(nombre); cellStyle = dataStyle }
                row.createCell(1).apply { setCellValue(quizzesFinalizados.size.toDouble()); cellStyle = dataStyle }
                row.createCell(2).apply { setCellValue("%.1f".format(quizzesFinalizados.map { it.preguntasCorrectas }.average())); cellStyle = dataStyle }
                row.createCell(3).apply { setCellValue(quizzesFinalizados.sumOf { it.experienciaGanada }.toDouble()); cellStyle = dataStyle }
                row.createCell(4).apply { setCellValue(quizzesFinalizados.map { it.temaId }.distinct().size.toDouble()); cellStyle = dataStyle }
            }
        }

        headers.indices.forEach { sheet.autoSizeColumn(it) }
        val out = ByteArrayOutputStream()
        workbook.write(out)
        workbook.close()

        return Pair(out.toByteArray(), "reporte_general_${curso.titulo.replace(" ", "_")}.xlsx")
    }

    suspend fun generarReporteRangoFechas(cursoId: String, desde: LocalDate, hasta: LocalDate): Pair<ByteArray, String> {
        val workbook = XSSFWorkbook()
        val curso = cursoRepo.obtenerCursoPorId(cursoId) ?: throw IllegalStateException("Curso no encontrado")
        val sheet = workbook.createSheet("Reporte Rango")
        val headerStyle = crearEstiloEncabezado(workbook)
        val dataStyle = crearEstiloDatos(workbook)
        var rowNum = 0

        sheet.createRow(rowNum++).createCell(0).apply { setCellValue("Reporte Rango - ${curso.titulo} ($desde a $hasta)"); cellStyle = headerStyle }
        rowNum++

        val headers = listOf("Estudiante", "Quizzes en Rango", "Promedio Correctas", "Experiencia", "Tiempo Promedio (seg)")
        val headerRow = sheet.createRow(rowNum++)
        headers.forEachIndexed { i, h -> headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle } }

        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
        estudiantes.forEach { est ->
            val estudianteId = est["userId"] as? String ?: ""
            val nombre = est["nombre"] as? String ?: "N/A"
            
            val todosQuizzes = obtenerQuizzesSimplificados(estudianteId, cursoId)
            val quizzesRango = todosQuizzes.filter { quiz ->
                val fechaQuiz = parsearFechaQuiz(quiz.inicio)
                fechaQuiz != null && !fechaQuiz.isBefore(desde) && !fechaQuiz.isAfter(hasta)
            }

            if (quizzesRango.isNotEmpty()) {
                val row = sheet.createRow(rowNum++)
                row.createCell(0).apply { setCellValue(nombre); cellStyle = dataStyle }
                row.createCell(1).apply { setCellValue(quizzesRango.size.toDouble()); cellStyle = dataStyle }
                row.createCell(2).apply { setCellValue("%.1f".format(quizzesRango.map { it.preguntasCorrectas }.average())); cellStyle = dataStyle }
                row.createCell(3).apply { setCellValue(quizzesRango.sumOf { it.experienciaGanada }.toDouble()); cellStyle = dataStyle }
                row.createCell(4).apply { setCellValue("%.1f".format(quizzesRango.map { it.tiempoUsadoSeg }.average())); cellStyle = dataStyle }
            }
        }

        headers.indices.forEach { sheet.autoSizeColumn(it) }
        val out = ByteArrayOutputStream()
        workbook.write(out)
        workbook.close()

        return Pair(out.toByteArray(), "reporte_rango_${curso.titulo.replace(" ", "_")}_${desde}_${hasta}.xlsx")
    }
}