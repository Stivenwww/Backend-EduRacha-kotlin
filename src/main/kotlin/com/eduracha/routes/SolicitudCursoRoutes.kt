package com.eduracha.routes

import com.eduracha.models.*
import com.eduracha.repository.SolicitudCursoRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import com.eduracha.services.ServicioProgreso
import com.google.firebase.auth.FirebaseAuth

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
            // 1. Cambiar estado de la solicitud
            repo.actualizarEstadoSolicitud(solicitudId, EstadoSolicitud.ACEPTADA, request.mensaje)
            
            // 2. Agregar estudiante al curso
            repo.agregarEstudianteACurso(
                solicitud.cursoId,
                solicitud.estudianteId,
                solicitud.estudianteNombre,
                solicitud.estudianteEmail
            )
            
            // 3. Inicializar progreso del estudiante (ESTRUCTURA UNIFICADA)
            ServicioProgreso.inicializarProgresoEstudiante(
                estudianteId = solicitud.estudianteId,
                cursoId = solicitud.cursoId,
                nombre = solicitud.estudianteNombre,
                email = solicitud.estudianteEmail ?: "sin-email@ejemplo.com"
            )

            call.respond(
                HttpStatusCode.OK,
                mapOf("message" to "Solicitud aceptada y estudiante agregado al curso con progreso inicializado")
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
 
// Obtener racha del estudiante autenticado
get("/curso/{cursoId}/racha") {
    try {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

        val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
        val userId = decodedToken.uid

        val cursoId = call.parameters["cursoId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el ID del curso"))

        val data = ServicioProgreso.obtenerProgreso(userId, cursoId)
        
        if (data != null) {
            call.respond(HttpStatusCode.OK, data)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("mensaje" to "No se encontró progreso"))
        }
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
    }
}

// Obtener experiencia del estudiante
get("/curso/{cursoId}/experiencia") {
    try {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

        val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
        val userId = decodedToken.uid

        val cursoId = call.parameters["cursoId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el ID del curso"))

        val progreso = ServicioProgreso.obtenerProgreso(userId, cursoId)
        
        if (progreso != null) {
            val experiencia = (progreso["experiencia"] as? Number)?.toInt() ?: 0
            val vidas = (progreso["vidas"] as? Number)?.toInt() ?: 5
            val diasConsecutivos = (progreso["diasConsecutivos"] as? Number)?.toInt() ?: 0
            
            call.respond(HttpStatusCode.OK, mapOf(
                "experiencia" to experiencia,
                "vidas" to vidas,
                "racha" to diasConsecutivos
            ))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("mensaje" to "No se encontró información"))
        }
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
    }
}

// A Obtener progreso de un estudiante específico
get("/curso/{cursoId}/estudiante/{estudianteId}/progreso") {
    try {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

        val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
        val claims = decodedToken.claims
        val rol = claims["rol"] as? String

        if (rol != "docente" && rol != "admin") {
            return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No tienes permisos"))
        }

        val cursoId = call.parameters["cursoId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el ID del curso"))

        val estudianteId = call.parameters["estudianteId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el ID del estudiante"))

        val data = ServicioProgreso.obtenerProgreso(estudianteId, cursoId)
        
        if (data != null) {
            call.respond(HttpStatusCode.OK, data)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("mensaje" to "No se encontró progreso"))
        }
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
    }
}

// Regenerar vidas manualmente (opcional)
post("/curso/{cursoId}/regenerar-vidas") {
    try {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

        val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
        val userId = decodedToken.uid

        val cursoId = call.parameters["cursoId"]
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el ID del curso"))

        ServicioProgreso.regenerarVidas(userId, cursoId, vidaRegenMinutos = 30)
        
        val progreso = ServicioProgreso.obtenerProgreso(userId, cursoId)
        val vidas = (progreso?.get("vidas") as? Number)?.toInt() ?: 5
        
        call.respond(HttpStatusCode.OK, mapOf(
            "mensaje" to "Vidas regeneradas",
            "vidasActuales" to vidas
        ))
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
    }
}
        }
    }
}