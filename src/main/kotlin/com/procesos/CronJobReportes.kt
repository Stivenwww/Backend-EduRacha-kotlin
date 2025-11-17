package com.eduracha.jobs

import com.eduracha.services.ServicioReportesExcel
import com.eduracha.repository.CursoRepository
import com.google.firebase.database.*
import kotlinx.coroutines.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cron Job para generar reportes Excel diarios automáticamente
 */
class CronJobReportes(
    private val servicioReportes: ServicioReportesExcel,
    private val cursoRepo: CursoRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var timer: Timer? = null

    companion object {
        const val HORA_EJECUCION = 23
        const val MINUTO_EJECUCION = 59
    }

    fun iniciar() {
        println("⏱ Iniciando Cron Job de reportes...")

        val ahora = LocalDateTime.now(ZoneId.systemDefault())
        var proximaEjecucion = ahora
            .withHour(HORA_EJECUCION)
            .withMinute(MINUTO_EJECUCION)
            .withSecond(0)

        if (ahora.isAfter(proximaEjecucion)) {
            proximaEjecucion = proximaEjecucion.plusDays(1)
        }

        val delayInicial = java.time.Duration.between(ahora, proximaEjecucion).toMillis()
        val periodo = 24 * 60 * 60 * 1000L

        println(" Próxima ejecución programada: $proximaEjecucion")

        timer = Timer("CronJobReportes", true)
        timer?.scheduleAtFixedRate(delayInicial, periodo) {
            scope.launch {
                ejecutarGeneracionReportes()
            }
        }
    }

    fun detener() {
        timer?.cancel()
        scope.cancel()
        println(" Cron Job de reportes detenido")
    }

    private suspend fun ejecutarGeneracionReportes() {
        println(" Ejecutando generación automática de reportes...")

        val fecha = LocalDate.now().minusDays(1)

        try {
            val cursos = cursoRepo.obtenerCursos()
            println(" Cursos encontrados: ${cursos.size}")

            cursos.forEach { curso ->
                try {
                    val cursoId = curso.id ?: return@forEach
                    println("    Generando reporte para curso: ${curso.titulo} ($cursoId)")

                    val (excelBytes, rutaSugerida) =
                        servicioReportes.generarReporteDiario(cursoId, fecha)

                    // Guardar referencia en Firebase Database
                    val rutaArchivo = rutaSugerida
                    guardarReferenciaReportePorFecha(cursoId, fecha, rutaArchivo, excelBytes.size)

                    // Notificar docente
                    notificarDocente(curso.docenteId, cursoId, rutaArchivo)

                    println("    Reporte generado: $rutaArchivo (${excelBytes.size} bytes)")

                } catch (e: Exception) {
                    println("    Error generando reporte para ${curso.titulo}: ${e.message}")
                }
            }

            println(" Generación de reportes completada")

        } catch (e: Exception) {
            println(" Error en cron job: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Guardar referencia del reporte en:
     * reportes_generados/{cursoId}/diario/{YYYY-MM-DD}
     */
    private suspend fun guardarReferenciaReportePorFecha(
        cursoId: String,
        fecha: LocalDate,
        rutaArchivo: String,
        tamanioBytes: Int
    ) = suspendCancellableCoroutine<Unit> { cont ->

        val db = FirebaseDatabase.getInstance()
        val fechaStr = fecha.format(DateTimeFormatter.ISO_DATE)
        val ref = db.getReference("reportes_generados")
            .child(cursoId)
            .child("diario")
            .child(fechaStr)

        val reporte = mapOf(
            "ruta" to rutaArchivo,
            "tamanio" to tamanioBytes,
            "fechaTimestamp" to System.currentTimeMillis(),
            "tipo" to "diario"
        )

        ref.setValue(reporte, DatabaseReference.CompletionListener { error, _ ->
            if (error != null) cont.resumeWithException(error.toException())
            else cont.resume(Unit)
        })
    }

    /**
     * Notificar al docente que el reporte está listo.
     */
    private suspend fun notificarDocente(
        docenteId: String,
        cursoId: String,
        rutaArchivo: String
    ) = suspendCancellableCoroutine<Unit> { cont ->

        val db = FirebaseDatabase.getInstance()
        val refNotif = db.getReference("notificaciones/$docenteId").push()

        val notificacion = mapOf(
            "titulo" to "Reporte diario generado",
            "mensaje" to "El reporte diario del curso $cursoId está listo",
            "tipo" to "reporte",
            "cursoId" to cursoId,
            "rutaArchivo" to rutaArchivo,
            "fecha" to System.currentTimeMillis(),
            "leido" to false
        )

        refNotif.setValue(notificacion, DatabaseReference.CompletionListener { error, _ ->
            if (error != null) {
                println(" Error al notificar docente: ${error.message}")
                cont.resumeWithException(error.toException())
            } else {
                cont.resume(Unit)
            }
        })
    }

    /**
     * Ejecutar manualmente el cron (desde un botón o endpoint).
     */
    suspend fun ejecutarManualmente() {
        println(" Ejecución manual de reportes...")
        ejecutarGeneracionReportes()
    }
}
