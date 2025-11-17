package com.eduracha.routes

import com.eduracha.services.ServicioReportesExcel
import com.eduracha.utils.getUserFromToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class DatosReporteDiarioResponse(
    val cursoId: String,
    val cursoNombre: String,
    val fecha: String,
    val totalEstudiantes: Int,
    val estudiantesConDatos: Int,
    val estudiantes: List<EstudianteReporteData>
)

@Serializable
data class EstudianteReporteData(
    val estudianteId: String,
    val nombre: String,
    val quizzesRealizados: Int,
    val promedioCorrectas: String,
    val experienciaGanada: Int
)

@Serializable
data class DatosReporteTemaResponse(
    val cursoId: String,
    val cursoNombre: String,
    val temaId: String,
    val temaNombre: String,
    val totalEstudiantes: Int,
    val estudiantesConDatos: Int,
    val estudiantes: List<EstudianteTemaData>
)

@Serializable
data class EstudianteTemaData(
    val estudianteId: String,
    val nombre: String,
    val intentos: Int,
    val mejorPuntaje: Int,
    val promedioTiempo: String,
    val estado: String
)

@Serializable
data class DatosReporteGeneralResponse(
    val cursoId: String,
    val cursoNombre: String,
    val totalEstudiantes: Int,
    val estudiantesConDatos: Int,
    val estudiantes: List<EstudianteGeneralData>
)

@Serializable
data class EstudianteGeneralData(
    val estudianteId: String,
    val nombre: String,
    val totalQuizzes: Int,
    val promedioGeneral: String,
    val experienciaTotal: Int,
    val temasCompletados: Int
)

@Serializable
data class DatosReporteRangoResponse(
    val cursoId: String,
    val cursoNombre: String,
    val fechaInicio: String,
    val fechaFin: String,
    val totalEstudiantes: Int,
    val estudiantesConDatos: Int,
    val estudiantes: List<EstudianteRangoData>
)

@Serializable
data class EstudianteRangoData(
    val estudianteId: String,
    val nombre: String,
    val quizzesEnRango: Int,
    val promedioCorrectas: String,
    val experiencia: Int,
    val tiempoPromedio: String
)

fun Route.reportesRoutes(servicioReportes: ServicioReportesExcel) {

    route("/reportes") {

        get("/debug/cursos/{cursoId}/diario/fecha/{fecha}") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No autorizado"))

                if (usuario.rol !in listOf("docente", "admin"))
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso denegado"))

                val cursoId = call.parameters["cursoId"]!!
                val fecha = LocalDate.parse(call.parameters["fecha"]!!)

                val datos = servicioReportes.obtenerDatosReporteDiarioSerializable(cursoId, fecha)
                call.respond(HttpStatusCode.OK, datos)

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
            }
        }

        get("/debug/cursos/{cursoId}/tema/{temaId}") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No autorizado"))

                if (usuario.rol !in listOf("docente", "admin"))
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso denegado"))

                val cursoId = call.parameters["cursoId"]!!
                val temaId = call.parameters["temaId"]!!

                val datos = servicioReportes.obtenerDatosReporteTemaSerializable(cursoId, temaId)
                call.respond(HttpStatusCode.OK, datos)

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
            }
        }

        get("/debug/cursos/{cursoId}/general") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No autorizado"))

                if (usuario.rol !in listOf("docente", "admin"))
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso denegado"))

                val cursoId = call.parameters["cursoId"]!!

                val datos = servicioReportes.obtenerDatosReporteGeneralSerializable(cursoId)
                call.respond(HttpStatusCode.OK, datos)

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
            }
        }

        get("/debug/cursos/{cursoId}/rango") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No autorizado"))

                if (usuario.rol !in listOf("docente", "admin"))
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso denegado"))

                val cursoId = call.parameters["cursoId"]!!
                val desde = LocalDate.parse(call.request.queryParameters["desde"]!!)
                val hasta = LocalDate.parse(call.request.queryParameters["hasta"]!!)

                val datos = servicioReportes.obtenerDatosReporteRangoSerializable(cursoId, desde, hasta)
                call.respond(HttpStatusCode.OK, datos)

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
            }
        }

        get("/cursos/{cursoId}/diario/fecha/{fecha}") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No autorizado"))

                if (usuario.rol !in listOf("docente", "admin"))
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso denegado"))

                val cursoId = call.parameters["cursoId"]!!
                val fecha = LocalDate.parse(call.parameters["fecha"]!!)

                val (bytes, nombre) = servicioReportes.generarReporteDiario(cursoId, fecha)

                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$nombre\"")
                call.respondBytes(bytes, contentType = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
            }
        }

        get("/cursos/{cursoId}/tema/{temaId}") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No autorizado"))

                if (usuario.rol !in listOf("docente", "admin"))
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso denegado"))

                val cursoId = call.parameters["cursoId"]!!
                val temaId = call.parameters["temaId"]!!

                val (bytes, nombre) = servicioReportes.generarReportePorTema(cursoId, temaId)

                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$nombre\"")
                call.respondBytes(bytes, contentType = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
            }
        }

        get("/cursos/{cursoId}/general") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No autorizado"))

                if (usuario.rol !in listOf("docente", "admin"))
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso denegado"))

                val cursoId = call.parameters["cursoId"]!!

                val (bytes, nombre) = servicioReportes.generarReporteGeneralCurso(cursoId)

                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$nombre\"")
                call.respondBytes(bytes, contentType = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
            }
        }

        get("/cursos/{cursoId}/rango") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "No autorizado"))

                if (usuario.rol !in listOf("docente", "admin"))
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso denegado"))

                val cursoId = call.parameters["cursoId"]!!
                val desde = LocalDate.parse(call.request.queryParameters["desde"]!!)
                val hasta = LocalDate.parse(call.request.queryParameters["hasta"]!!)

                val (bytes, nombre) = servicioReportes.generarReporteRangoFechas(cursoId, desde, hasta)

                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$nombre\"")
                call.respondBytes(bytes, contentType = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))

            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
            }
        }
    }
}