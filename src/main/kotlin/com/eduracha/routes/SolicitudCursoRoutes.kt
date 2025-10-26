package com.eduracha.routes

import com.eduracha.models.*
import com.eduracha.repository.SolicitudCursoRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*

fun Application.solicitudCursoRoutes() {
    val repo = SolicitudCursoRepository()

    routing {
        route("/api/solicitudes") {

            // Estudiante solicita unirse con código
            post("/unirse") {
                try {
                    val request = call.receive<SolicitudRequest>()

                    val curso = repo.buscarCursoPorCodigo(request.codigoCurso)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Código de curso inválido")
                        )

                    val existe = repo.verificarSolicitudExistente(request.estudianteId, curso.id!!)
                    if (existe) {
                        return@post call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "Ya existe una solicitud para este curso")
                        )
                    }

                    val solicitud = SolicitudCurso(
                        cursoId = curso.id,
                        codigoCurso = curso.codigo,
                        estudianteId = request.estudianteId,
                        estudianteNombre = request.estudianteNombre,
                        estudianteEmail = request.estudianteEmail,
                        fechaSolicitud = System.currentTimeMillis().toString(),
                        mensaje = request.mensaje
                    )

                    val id = repo.crearSolicitud(solicitud)
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf("message" to "Solicitud enviada", "id" to id)
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al crear la solicitud: ${e.message}")
                    )
                }
            }

            // Ver solicitudes pendientes del docente
            get("/docente/{docenteId}") {
                val docenteId = call.parameters["docenteId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Falta el ID del docente")
                    )
                try {
                    val solicitudes = repo.obtenerSolicitudesPendientesPorDocente(docenteId)
                    if (solicitudes.isEmpty()) {
                        call.respond(HttpStatusCode.NotFound, mapOf("mensaje" to "No hay solicitudes pendientes"))
                    } else {
                        call.respond(HttpStatusCode.OK, solicitudes)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener solicitudes: ${e.message}")
                    )
                }
            }

            // Ver solicitudes del estudiante
            get("/estudiante/{estudianteId}") {
                val estudianteId = call.parameters["estudianteId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Falta el ID del estudiante")
                    )
                try {
                    val solicitudes = repo.obtenerSolicitudesPorEstudiante(estudianteId)
                    if (solicitudes.isEmpty()) {
                        call.respond(HttpStatusCode.NotFound, mapOf("mensaje" to "No hay solicitudes registradas"))
                    } else {
                        call.respond(HttpStatusCode.OK, solicitudes)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener solicitudes: ${e.message}")
                    )
                }
            }

            // Profesor responde (aceptar o rechazar)
            post("/responder/{solicitudId}") {
                val solicitudId = call.parameters["solicitudId"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Falta el ID de la solicitud")
                    )

                try {
                    val request = call.receive<RespuestaSolicitudRequest>()
                    val solicitud = repo.obtenerSolicitudPorId(solicitudId)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Solicitud no encontrada")
                        )

                    if (request.aceptar) {
                        // Cambiar estado y agregar estudiante con sus datos al curso
                        repo.actualizarEstadoSolicitud(solicitudId, EstadoSolicitud.ACEPTADA, request.mensaje)
                        repo.agregarEstudianteACurso(
                            solicitud.cursoId,
                            solicitud.estudianteId,
                            solicitud.estudianteNombre,
                            solicitud.estudianteEmail
                        )

                        call.respond(
                            HttpStatusCode.OK,
                            mapOf("message" to "Solicitud aceptada y estudiante agregado al curso")
                        )
                    } else {
                        repo.actualizarEstadoSolicitud(solicitudId, EstadoSolicitud.RECHAZADA, request.mensaje)
                        call.respond(HttpStatusCode.OK, mapOf("message" to "Solicitud rechazada"))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al procesar la solicitud: ${e.message}")
                    )
                }
            }

            // Ver detalle de una solicitud
            get("/{solicitudId}") {
                val solicitudId = call.parameters["solicitudId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Falta el ID de la solicitud")
                    )
                try {
                    val solicitud = repo.obtenerSolicitudPorId(solicitudId)
                    if (solicitud == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Solicitud no encontrada"))
                    } else {
                        call.respond(HttpStatusCode.OK, solicitud)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener solicitud: ${e.message}")
                    )
                }
            }

            // Obtener estudiantes de un curso
            get("/curso/{id}/estudiantes") {
                val cursoId = call.parameters["id"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Falta el ID del curso")
                    )

                try {
                    val cursoRepo = com.eduracha.repository.CursoRepository()
                    val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)

                    if (estudiantes.isEmpty()) {
                        call.respond(HttpStatusCode.NotFound, mapOf("mensaje" to "No hay estudiantes en este curso"))
                    } else {
                        call.respond(HttpStatusCode.OK, estudiantes)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener estudiantes: ${e.message}")
                    )
                }
            }
            // Cambiar estado de estudiante en curso
post("/curso/{cursoId}/estudiante/{estudianteId}/estado") {
    val cursoId = call.parameters["cursoId"]
        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el ID del curso"))

    val estudianteId = call.parameters["estudianteId"]
        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el ID del estudiante"))

    val request = call.receive<Map<String, String>>()
    val nuevoEstado = request["estado"] ?: return@post call.respond(
        HttpStatusCode.BadRequest,
        mapOf("error" to "Falta el campo 'estado'")
    )

    if (nuevoEstado !in listOf("activo", "inactivo", "eliminado")) {
        return@post call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Estado inválido. Use: activo | inactivo | eliminado")
        )
    }

    try {
        repo.cambiarEstadoEstudiante(cursoId, estudianteId, nuevoEstado)
        call.respond(HttpStatusCode.OK, mapOf("mensaje" to "Estado actualizado a $nuevoEstado"))
    } catch (e: Exception) {
        e.printStackTrace()
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
    }
}

        }
    }
}
