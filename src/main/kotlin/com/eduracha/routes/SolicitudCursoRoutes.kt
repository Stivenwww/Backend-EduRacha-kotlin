package com.eduracha.routes

import com.eduracha.models.*
import com.eduracha.repository.SolicitudCursoRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.solicitudCursoRoutes() {
    val repo = SolicitudCursoRepository()

    routing {
        route("/api/solicitudes") {

            //  Estudiante solicita unirse con código
            post("/unirse") {
                val request = call.receive<SolicitudRequest>()
                val curso = repo.buscarCursoPorCodigo(request.codigoCurso)
                    ?: return@post call.respond(HttpStatusCode.NotFound, "Código de curso inválido")

                val existe = repo.verificarSolicitudExistente(request.estudianteId, curso.id!!)
                if (existe) return@post call.respond(HttpStatusCode.Conflict, "Ya existe una solicitud para este curso")

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
                call.respond(mapOf("message" to "Solicitud enviada", "id" to id))
            }

            //  Ver solicitudes pendientes del docente
            get("/docente/{docenteId}") {
                val docenteId = call.parameters["docenteId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val solicitudes = repo.obtenerSolicitudesPendientesPorDocente(docenteId)
                call.respond(solicitudes)
            }

            //  Ver solicitudes del estudiante
            get("/estudiante/{estudianteId}") {
                val estudianteId = call.parameters["estudianteId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val solicitudes = repo.obtenerSolicitudesPorEstudiante(estudianteId)
                call.respond(solicitudes)
            }

            //  Profesor responde (aceptar o rechazar)
            post("/responder/{solicitudId}") {
                val solicitudId = call.parameters["solicitudId"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val request = call.receive<RespuestaSolicitudRequest>()
                val solicitud = repo.obtenerSolicitudPorId(solicitudId)
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                if (request.aceptar) {
                    repo.actualizarEstadoSolicitud(solicitudId, EstadoSolicitud.ACEPTADA, request.mensaje)
                    repo.agregarEstudianteACurso(solicitud.cursoId, solicitud.estudianteId)
                    call.respond(mapOf("message" to "Solicitud aceptada y estudiante agregado"))
                } else {
                    repo.actualizarEstadoSolicitud(solicitudId, EstadoSolicitud.RECHAZADA, request.mensaje)
                    call.respond(mapOf("message" to "Solicitud rechazada"))
                }
            }

            //  Ver detalle de una solicitud
            get("/{solicitudId}") {
                val solicitudId = call.parameters["solicitudId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val solicitud = repo.obtenerSolicitudPorId(solicitudId)
                if (solicitud == null)
                    call.respond(HttpStatusCode.NotFound, "Solicitud no encontrada")
                else
                    call.respond(solicitud)
            }
        }
    }
}
