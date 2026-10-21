package com.eduracha.services

import com.eduracha.utils.lecturaFirebase
import com.eduracha.utils.reanudarConError

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
    ): List<DatosQuizSimple> = lecturaFirebase { cont ->
        
        println(" [ServicioReportes] Obteniendo quizzes simplificados:")
        println("    Estudiante: $estudianteId")
        println("    Curso: $cursoId")
        
        val database = FirebaseDatabase.getInstance()
        val refQuizzes = database.getReference("quizzes")
        
        refQuizzes.orderByChild("estudianteId").equalTo(estudianteId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                try {
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
                } catch (e: Exception) {
                    reanudarConError(cont, e, "ServicioReportesExcel.kt")
                }
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

            datosEstudiantes.add(EstudianteReporteData(estudianteId, nombre, total, "%.1f".format(java.util.Locale.US, promedio), xp))
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
                    "%.1f".format(java.util.Locale.US, promTiempo), 
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
                    "%.1f".format(java.util.Locale.US, promedioCorrectas), 
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
                    "%.1f".format(java.util.Locale.US, promedioCorrectas), 
                    xpTotal, 
                    "%.1f".format(java.util.Locale.US, tiempoPromedio)
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
    // Se arman con los mismos datos que ve la vista previa de la app y con el diseño de LibroExcelEduRacha.

    private fun nombreArchivo(prefijo: String, curso: String, sufijo: String = ""): String {
        val base = java.text.Normalizer.normalize(curso, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40).ifBlank { "curso" }
        return "EduRacha_${prefijo}_${base}${if (sufijo.isNotBlank()) "_$sufijo" else ""}.xlsx"
    }

    private fun num(texto: String): Double = texto.replace(",", ".").toDoubleOrNull() ?: 0.0

    suspend fun generarReporteDiario(cursoId: String, fecha: LocalDate): Pair<ByteArray, String> {
        val datos = obtenerDatosReporteDiarioSerializable(cursoId, fecha)
        val filas = datos.estudiantes.sortedByDescending { it.experienciaGanada }
        val reporte = LibroExcelEduRacha.Reporte(
            titulo = "Reporte diario",
            curso = datos.cursoNombre,
            detalle = "Fecha: ${fecha.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}",
            indicadores = listOf(
                LibroExcelEduRacha.Indicador("Estudiantes", "${datos.totalEstudiantes}"),
                LibroExcelEduRacha.Indicador("Con actividad", "${datos.estudiantesConDatos}"),
                LibroExcelEduRacha.Indicador("Quizzes", "${filas.sumOf { it.quizzesRealizados }}"),
                LibroExcelEduRacha.Indicador("XP ganada", "${filas.sumOf { it.experienciaGanada }}")
            ),
            tituloTabla = "Actividad de cada estudiante",
            columnas = listOf(
                LibroExcelEduRacha.Columna("Estudiante", ancho = 28),
                LibroExcelEduRacha.Columna("Quizzes realizados", LibroExcelEduRacha.TipoCelda.ENTERO),
                LibroExcelEduRacha.Columna("Promedio de aciertos", LibroExcelEduRacha.TipoCelda.DECIMAL),
                LibroExcelEduRacha.Columna("XP ganada", LibroExcelEduRacha.TipoCelda.ENTERO),
                LibroExcelEduRacha.Columna("Estado", LibroExcelEduRacha.TipoCelda.ESTADO)
            ),
            filas = filas.map {
                listOf(it.nombre, it.quizzesRealizados, num(it.promedioCorrectas), it.experienciaGanada,
                    if (it.quizzesRealizados > 0) "Activo" else "Sin actividad")
            },
            grafico = LibroExcelEduRacha.Grafico("XP ganada en el día", 0, 3, "XP")
        )
        // Resumen diario por estudiante (se mantiene el comportamiento anterior)
        datos.estudiantes.forEach { e ->
            try {
                guardarResumenDiarioUsuario(e.estudianteId, fecha, mapOf(
                    "cursoId" to cursoId,
                    "cursoNombre" to datos.cursoNombre,
                    "fecha" to fecha.toString(),
                    "quizzesRealizados" to e.quizzesRealizados,
                    "promedioCorrectas" to e.promedioCorrectas,
                    "experienciaGanada" to e.experienciaGanada
                ))
            } catch (ex: Exception) {
                println(" Error guardando resumen para ${e.estudianteId}: ${ex.message}")
            }
        }
        return LibroExcelEduRacha.crear(reporte) to nombreArchivo("Diario", datos.cursoNombre, fecha.toString())
    }

    private suspend fun guardarResumenDiarioUsuario(uid: String, fecha: LocalDate, resumen: Map<String, Any>) = lecturaFirebase<Unit> { cont ->
        try {
            val fechaStr = fecha.format(DateTimeFormatter.ISO_DATE)
            val ref = FirebaseDatabase.getInstance().getReference("usuarios").child(uid).child("actividad_diaria").child(fechaStr)
            ref.setValue(resumen) { error, _ ->
                if (error != null) {
                    println(" Error guardando actividad diaria: ${error.message}")
                    cont.resumeWithException(error.toException())
                } else {
                    cont.resume(Unit)
                }
            }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    suspend fun generarReportePorTema(cursoId: String, temaId: String): Pair<ByteArray, String> {
        val datos = obtenerDatosReporteTemaSerializable(cursoId, temaId)
        val filas = datos.estudiantes.sortedByDescending { it.mejorPuntaje }
        val aprobados = filas.count { it.estado.equals("Aprobado", true) }
        val reporte = LibroExcelEduRacha.Reporte(
            titulo = "Reporte por tema",
            curso = datos.cursoNombre,
            detalle = "Tema: ${datos.temaNombre}",
            indicadores = listOf(
                LibroExcelEduRacha.Indicador("Estudiantes", "${datos.totalEstudiantes}"),
                LibroExcelEduRacha.Indicador("Presentaron", "${datos.estudiantesConDatos}"),
                LibroExcelEduRacha.Indicador("Aprobaron", "$aprobados"),
                LibroExcelEduRacha.Indicador("Intentos", "${filas.sumOf { it.intentos }}")
            ),
            tituloTabla = "Resultados del tema",
            columnas = listOf(
                LibroExcelEduRacha.Columna("Estudiante", ancho = 28),
                LibroExcelEduRacha.Columna("Intentos", LibroExcelEduRacha.TipoCelda.ENTERO),
                LibroExcelEduRacha.Columna("Mejor puntaje", LibroExcelEduRacha.TipoCelda.ENTERO),
                LibroExcelEduRacha.Columna("Tiempo promedio (s)", LibroExcelEduRacha.TipoCelda.DECIMAL),
                LibroExcelEduRacha.Columna("Estado", LibroExcelEduRacha.TipoCelda.ESTADO)
            ),
            filas = filas.map { listOf(it.nombre, it.intentos, it.mejorPuntaje, num(it.promedioTiempo), it.estado) },
            grafico = LibroExcelEduRacha.Grafico("Mejor puntaje por estudiante", 0, 2, "Aciertos")
        )
        return LibroExcelEduRacha.crear(reporte) to nombreArchivo("Tema", datos.cursoNombre, datos.temaNombre)
    }

    suspend fun generarReporteGeneralCurso(cursoId: String): Pair<ByteArray, String> {
        val datos = obtenerDatosReporteGeneralSerializable(cursoId)
        val filas = datos.estudiantes.sortedByDescending { it.experienciaTotal }
        val reporte = LibroExcelEduRacha.Reporte(
            titulo = "Reporte general del curso",
            curso = datos.cursoNombre,
            detalle = "Todo el historial del curso",
            indicadores = listOf(
                LibroExcelEduRacha.Indicador("Estudiantes", "${datos.totalEstudiantes}"),
                LibroExcelEduRacha.Indicador("Con actividad", "${datos.estudiantesConDatos}"),
                LibroExcelEduRacha.Indicador("Quizzes", "${filas.sumOf { it.totalQuizzes }}"),
                LibroExcelEduRacha.Indicador("XP total", "${filas.sumOf { it.experienciaTotal }}")
            ),
            tituloTabla = "Ranking y avance de cada estudiante",
            columnas = listOf(
                LibroExcelEduRacha.Columna("Puesto", LibroExcelEduRacha.TipoCelda.ENTERO, ancho = 8),
                LibroExcelEduRacha.Columna("Estudiante", ancho = 28),
                LibroExcelEduRacha.Columna("Quizzes", LibroExcelEduRacha.TipoCelda.ENTERO),
                LibroExcelEduRacha.Columna("Promedio de aciertos", LibroExcelEduRacha.TipoCelda.DECIMAL),
                LibroExcelEduRacha.Columna("XP total", LibroExcelEduRacha.TipoCelda.ENTERO),
                LibroExcelEduRacha.Columna("Temas completados", LibroExcelEduRacha.TipoCelda.ENTERO)
            ),
            filas = filas.mapIndexed { i, it ->
                listOf(i + 1, it.nombre, it.totalQuizzes, num(it.promedioGeneral), it.experienciaTotal, it.temasCompletados)
            },
            grafico = LibroExcelEduRacha.Grafico("XP total por estudiante", 1, 4, "XP")
        )
        return LibroExcelEduRacha.crear(reporte) to nombreArchivo("General", datos.cursoNombre)
    }

    suspend fun generarReporteRangoFechas(cursoId: String, desde: LocalDate, hasta: LocalDate): Pair<ByteArray, String> {
        val datos = obtenerDatosReporteRangoSerializable(cursoId, desde, hasta)
        val filas = datos.estudiantes.sortedByDescending { it.experiencia }
        val f = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val reporte = LibroExcelEduRacha.Reporte(
            titulo = "Reporte por rango de fechas",
            curso = datos.cursoNombre,
            detalle = "Del ${desde.format(f)} al ${hasta.format(f)}",
            indicadores = listOf(
                LibroExcelEduRacha.Indicador("Estudiantes", "${datos.totalEstudiantes}"),
                LibroExcelEduRacha.Indicador("Con actividad", "${datos.estudiantesConDatos}"),
                LibroExcelEduRacha.Indicador("Quizzes", "${filas.sumOf { it.quizzesEnRango }}"),
                LibroExcelEduRacha.Indicador("XP ganada", "${filas.sumOf { it.experiencia }}")
            ),
            tituloTabla = "Actividad en el rango",
            columnas = listOf(
                LibroExcelEduRacha.Columna("Estudiante", ancho = 28),
                LibroExcelEduRacha.Columna("Quizzes", LibroExcelEduRacha.TipoCelda.ENTERO),
                LibroExcelEduRacha.Columna("Promedio de aciertos", LibroExcelEduRacha.TipoCelda.DECIMAL),
                LibroExcelEduRacha.Columna("XP ganada", LibroExcelEduRacha.TipoCelda.ENTERO),
                LibroExcelEduRacha.Columna("Tiempo promedio (s)", LibroExcelEduRacha.TipoCelda.DECIMAL)
            ),
            filas = filas.map { listOf(it.nombre, it.quizzesEnRango, num(it.promedioCorrectas), it.experiencia, num(it.tiempoPromedio)) },
            grafico = LibroExcelEduRacha.Grafico("XP ganada en el rango", 0, 3, "XP")
        )
        return LibroExcelEduRacha.crear(reporte) to nombreArchivo("Rango", datos.cursoNombre, "${desde}_a_${hasta}")
    }
}

/** Número con un decimal (celda numérica en Excel, no texto; evita NaN cuando no hay datos). */
fun redondear1(valor: Double): Double = if (valor.isNaN() || valor.isInfinite()) 0.0 else Math.round(valor * 10) / 10.0
