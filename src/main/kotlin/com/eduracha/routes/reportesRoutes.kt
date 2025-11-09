package com.eduracha.routes

import com.eduracha.services.ServicioReportesExcel
import com.eduracha.utils.getUserFromToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate

fun Route.reportesRoutes(servicioReportes: ServicioReportesExcel) {
    
    route("/reportes") {
        
        // GET /reportes/diario/{cursoId} - Generar reporte diario
        get("/diario/{cursoId}") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Token inválido o expirado")
                    )
                
                // Verificar que sea docente o admin
                if (usuario.rol != "docente" && usuario.rol != "admin") {
                    return@get call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No tienes permisos para generar reportes")
                    )
                }
                
                val cursoId = call.parameters["cursoId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Curso ID es requerido")
                    )
                
                // Obtener fecha opcional, por defecto ayer
                val fechaParam = call.request.queryParameters["fecha"]
                val fecha = if (fechaParam != null) {
                    LocalDate.parse(fechaParam)
                } else {
                    LocalDate.now().minusDays(1)
                }
                
                val excelBytes = servicioReportes.generarReporteDiario(cursoId, fecha)
                
                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"reporte_diario_${cursoId}_${fecha}.xlsx\""
                )
                
                call.respondBytes(
                    bytes = excelBytes,
                    contentType = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                )
                
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Error al generar reporte: ${e.message}")
                )
            }
        }
        
        // GET /reportes/tema/{cursoId}/{temaId} - Generar reporte por tema
        get("/tema/{cursoId}/{temaId}") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("error" to "Token inválido o expirado")
                    )
                
                // Verificar que sea docente o admin
                if (usuario.rol != "docente" && usuario.rol != "admin") {
                    return@get call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "No tienes permisos para generar reportes")
                    )
                }
                
                val cursoId = call.parameters["cursoId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Curso ID es requerido")
                    )
                
                val temaId = call.parameters["temaId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Tema ID es requerido")
                    )
                
                
                val excelBytes = servicioReportes.generarReportePorTema(cursoId, temaId)
                
                call.response.headers.append(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"reporte_tema_${temaId}.xlsx\""
                )
                
                call.respondBytes(
                    bytes = excelBytes,
                    contentType = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                )
                
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Error al generar reporte: ${e.message}")
                )
            }
        }
    }
}