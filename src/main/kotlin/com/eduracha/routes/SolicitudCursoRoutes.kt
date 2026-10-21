package com.eduracha.routes

import com.eduracha.utils.esDuenoOPrivilegiado
import com.eduracha.utils.exigirUsuario
import com.eduracha.utils.exigirRol
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
                val usuarioToken = call.exigirUsuario() ?: return@post
                try {
                    val request = call.receive<SolicitudRequest>()
                    // Un estudiante siempre solicita a su nombre: se usa el UID del token, no el que
                    // envía la app (antes, un UID vacío o desactualizado en el celular daba 403).
                    val estudianteId = if (usuarioToken.rol in setOf("docente", "admin") && request.estudianteId.isNotBlank())
                        request.estudianteId else usuarioToken.uid
                    if (request.codigoCurso.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el código del curso"))
                    }

                    val curso = repo.buscarCursoPorCodigo(request.codigoCurso)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "No encontramos un curso con ese código")
                        )
                    val cursoId = curso.id
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Curso no disponible"))
                    if (curso.estado.equals("inactivo", ignoreCase = true) || curso.estado.equals("archivado", ignoreCase = true)) {
                        return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Este curso no está recibiendo estudiantes"))
                    }

                    if (repo.estaInscrito(cursoId, estudianteId)) {
                        return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Ya estás inscrito en este curso"))
                    }
                    when (repo.solicitudQueBloquea(estudianteId, cursoId)) {
                        "PENDIENTE" -> return@post call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "Ya enviaste una solicitud para este curso. Espera la respuesta del docente.")
                        )
                        "ACEPTADA" -> return@post call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "Tu solicitud para este curso ya fue aceptada")
                        )
                    }

                    val solicitud = SolicitudCurso(
                        cursoId = cursoId,
                        codigoCurso = curso.codigo,
                        estudianteId = estudianteId,
                        estudianteNombre = request.estudianteNombre.ifBlank { usuarioToken.email.substringBefore("@") },
                        estudianteEmail = request.estudianteEmail.ifBlank { usuarioToken.email },
                        fechaSolicitud = System.currentTimeMillis().toString(),
                        mensaje = request.mensaje
                    )

                    val id = repo.crearSolicitud(solicitud)
                    com.eduracha.services.Notificador.enviar(
                        curso.docenteId,
                        "Nueva solicitud de inscripción",
                        "${solicitud.estudianteNombre} quiere unirse a ${curso.titulo}.",
                        "solicitud",
                        mapOf("cursoId" to cursoId, "estudianteId" to estudianteId)
                    )
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

            // GET /api/solicitudes/pendientes-docente — todo lo que el docente tiene por revisar (centro de pendientes)
            get("/pendientes-docente") {
                val docente = call.exigirRol("docente", "admin") ?: return@get
                try {
                    call.respond(HttpStatusCode.OK, com.eduracha.services.ServicioPendientes().obtener(docente.uid))
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudieron calcular los pendientes: ${e.message}"))
                }
            }

            // Ver solicitudes pendientes del docente
            get("/docente/{docenteId}") {
                val usuarioToken = call.exigirUsuario() ?: return@get
                if (usuarioToken.uid != call.parameters["docenteId"] && usuarioToken.rol != "admin") {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No autorizado"))
                }
                val docenteId = call.parameters["docenteId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Falta el ID del docente")
                    )
                try {
                    val solicitudes = repo.obtenerSolicitudesPendientesPorDocente(docenteId)
                    // Lista vacía = 200 [] (antes 404, que la app mostraba como error)
                    call.respond(HttpStatusCode.OK, solicitudes)
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
                val usuarioToken = call.exigirUsuario() ?: return@get
                if (!usuarioToken.esDuenoOPrivilegiado(call.parameters["estudianteId"] ?: "")) {
                    return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No autorizado"))
                }
                val estudianteId = call.parameters["estudianteId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Falta el ID del estudiante")
                    )
                try {
                    val solicitudes = repo.obtenerSolicitudesPorEstudiante(estudianteId)
                    // Lista vacía = 200 [] (antes 404, que la app mostraba como error)
                    call.respond(HttpStatusCode.OK, solicitudes)
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
    call.exigirRol("docente", "admin") ?: return@post
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
            com.eduracha.services.Notificador.enviar(
                solicitud.estudianteId,
                "Solicitud no aceptada",
                "El docente no aceptó tu solicitud al curso ${solicitud.codigoCurso}." +
                    (request.mensaje?.takeIf { it.isNotBlank() }?.let { " Mensaje: $it" } ?: " Puedes volver a solicitarlo."),
                "solicitud_rechazada",
                mapOf("cursoId" to solicitud.cursoId)
            )
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

                    // Lista vacía = 200 [] (antes 404, que la app mostraba como error)
                    call.respond(HttpStatusCode.OK, estudiantes)
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
    call.exigirRol("docente", "admin") ?: return@post
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
 //  1. Obtener racha del estudiante autenticado
get("/curso/{cursoId}/racha") {
    try {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

        val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
        val userId = decodedToken.uid

        val cursoId = call.parameters["cursoId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el ID del curso"))

        val progreso = ServicioProgreso.obtenerProgreso(userId, cursoId)
        
        if (progreso != null) {
            val respuesta = mapOf<String, Any>(
                "diasConsecutivos" to ((progreso["diasConsecutivos"] as? Number)?.toInt() ?: 0),
                "ultimaFecha" to ((progreso["ultimaFecha"] as? Number)?.toLong() ?: 0),
                "mejorRacha" to ((progreso["mejorRacha"] as? Number)?.toInt() ?: 0)
            )
            call.respond(HttpStatusCode.OK, respuesta)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("mensaje" to "No se encontró progreso"))
        }
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
    }
}

//  2. Obtener experiencia del estudiante autenticado
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
            val respuesta = mapOf<String, Any>(
                "experiencia" to ((progreso["experiencia"] as? Number)?.toInt() ?: 0),
                "vidas" to ((progreso["vidas"] as? Number)?.toInt() ?: 5),
                "vidasMax" to ((progreso["vidasMax"] as? Number)?.toInt() ?: 5),
                "diasConsecutivos" to ((progreso["diasConsecutivos"] as? Number)?.toInt() ?: 0)
            )
            call.respond(HttpStatusCode.OK, respuesta)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("mensaje" to "No se encontró información"))
        }
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
    }
}

//  3. Obtener progreso completo de un estudiante (solo docentes/admin)
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

        val progreso = ServicioProgreso.obtenerProgreso(estudianteId, cursoId)
        
        if (progreso != null) {
            call.respond(HttpStatusCode.OK, progreso)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("mensaje" to "No se encontró progreso"))
        }
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
    }
}


post("/curso/{cursoId}/regenerar-vidas") {
    call.exigirRol("docente", "admin") ?: return@post
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
        val vidasMax = (progreso?.get("vidasMax") as? Number)?.toInt() ?: 5
        
        val respuesta = mapOf<String, Any>(
            "mensaje" to "Vidas regeneradas",
            "vidasActuales" to vidas,
            "vidasMax" to vidasMax
        )
        call.respond(HttpStatusCode.OK, respuesta)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error desconocido")))
    }
}
        }
    }
}