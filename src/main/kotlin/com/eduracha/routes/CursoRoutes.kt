package com.eduracha.routes

import com.eduracha.models.Curso
import com.eduracha.models.Tema
import com.eduracha.repository.CursoRepository
import com.eduracha.repository.ExplicacionRepository
import com.eduracha.repository.SolicitudPreguntasRepository
import com.eduracha.services.OpenAIService
import com.eduracha.utils.getUserFromToken
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.github.cdimascio.dotenv.dotenv
import kotlinx.serialization.Serializable

@Serializable
data class TemaConExplicacionRequest(
    val tema: Tema,
    val explicacion: String? = null,
    val generarConIA: Boolean = false
)

@Serializable
data class TemaResponse(
    val message: String,
    val temaId: String,
    val tema: Tema,
    val requiereValidacion: Boolean = false
)

@Serializable
data class GenerarExplicacionRequest(
    val tituloTema: String,
    val contenidoTema: String? = null
)

@Serializable
data class ActualizarExplicacionRequest(
    val explicacion: String,
    val fuente: String = "docente"
)

@Serializable
data class ExplicacionResponse(
    val message: String,
    val explicacion: String,
    val fuente: String,
    val tema: Tema
)

fun Application.cursoRoutes() {
    val repo = CursoRepository()
    val explicacionRepo = ExplicacionRepository()
    val solicitudPreguntasRepo = SolicitudPreguntasRepository()
    
    val dotenv = dotenv()
    val openAiKey = dotenv["OPENAI_API_KEY"]
    val client = HttpClient(CIO)
    val iaService = if (!openAiKey.isNullOrEmpty()) {
        OpenAIService(client, openAiKey)
    } else null

    routing {
        route("/api/cursos") {

            post {
                val curso = call.receive<Curso>()
                val id = repo.crearCurso(curso)
                call.respond(mapOf("message" to "Curso creado exitosamente", "id" to id))
            }

            get {
                val cursos = repo.obtenerCursos()
                call.respond(cursos)
            }

            get("{id}") {
                val id = call.parameters["id"] ?: return@get call.respondText(
                    "Falta ID", 
                    status = HttpStatusCode.BadRequest
                )
                val curso = repo.obtenerCursoPorId(id)
                if (curso == null) {
                    call.respondText("Curso no encontrado", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(curso)
                }
            }

            put("{id}") {
                val id = call.parameters["id"] ?: return@put call.respondText(
                    "Falta ID", 
                    status = HttpStatusCode.BadRequest
                )
                val curso = call.receive<Curso>()
                repo.actualizarCurso(id, curso)
                call.respond(mapOf("message" to "Curso actualizado correctamente"))
            }

            delete("{id}") {
                val id = call.parameters["id"] ?: return@delete call.respondText(
                    "Falta ID", 
                    status = HttpStatusCode.BadRequest
                )
                repo.eliminarCurso(id)
                call.respond(mapOf("message" to "Curso eliminado"))
            }

            get("{id}/temas") {
                val cursoId = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )

                val estado = call.request.queryParameters["estado"]

                try {
                    val temas = if (!estado.isNullOrBlank()) {
                        explicacionRepo.obtenerTemasPorEstadoExplicacion(cursoId, estado)
                    } else {
                        explicacionRepo.obtenerTemasFiltrandoExplicacion(cursoId)
                    }

                    call.respond(HttpStatusCode.OK, temas)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener temas: ${e.message}")
                    )
                }
            }

            get("{id}/temas/{temaId}") {
                val cursoId = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )
                val temaId = call.parameters["temaId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del tema")
                )

                try {
                    val tema = repo.obtenerTema(cursoId, temaId)
                    if (tema == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Tema no encontrado"))
                    } else {
                        call.respond(HttpStatusCode.OK, tema)
                    }
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener tema: ${e.message}")
                    )
                }
            }

            post("{id}/temas/simple") {
                val cursoId = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )

                try {
                    val tema = call.receive<Tema>()
                    val temaId = repo.agregarTema(cursoId, tema)
                    val temaCreado = repo.obtenerTema(cursoId, temaId)

                    call.respond(
                        HttpStatusCode.Created,
                        TemaResponse(
                            message = "Tema creado. Recuerda agregar una explicación antes de generar preguntas.",
                            temaId = temaId,
                            tema = temaCreado!!,
                            requiereValidacion = false
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al crear tema: ${e.message}")
                    )
                }
            }

            post("{id}/temas") {
                val cursoId = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )

                try {
                    val request = call.receive<TemaConExplicacionRequest>()

                    if (!request.explicacion.isNullOrBlank() && !request.generarConIA) {
                        val temaId = repo.agregarTemaConExplicacion(
                            cursoId = cursoId,
                            tema = request.tema,
                            explicacion = request.explicacion
                        )
                        val temaCreado = repo.obtenerTema(cursoId, temaId)

                        call.respond(
                            HttpStatusCode.Created,
                            TemaResponse(
                                message = "Tema creado con explicación manual. Explicación aprobada automáticamente. Puedes generar preguntas.",
                                temaId = temaId,
                                tema = temaCreado!!,
                                requiereValidacion = false
                            )
                        )
                    }
                    else if (request.generarConIA) {
                        if (iaService == null) {
                            return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                mapOf("error" to "Servicio de IA no disponible. Verifica OPENAI_API_KEY en .env")
                            )
                        }

                        val temaId = repo.agregarTema(cursoId, request.tema)

                        val explicacionGenerada = iaService.generarExplicacion(
                            cursoId = cursoId,
                            temaId = temaId,
                            tituloTema = request.tema.titulo,
                            contenidoTema = request.tema.contenido
                        )

                        explicacionRepo.actualizarExplicacion(
                            cursoId = cursoId,
                            temaId = temaId,
                            explicacion = explicacionGenerada,
                            fuente = "ia",
                            estado = "pendiente"
                        )

                        val temaCreado = repo.obtenerTema(cursoId, temaId)

                        call.respond(
                            HttpStatusCode.Created,
                            TemaResponse(
                                message = "Tema creado con explicación generada por IA. Requiere validación antes de generar preguntas.",
                                temaId = temaId,
                                tema = temaCreado!!,
                                requiereValidacion = true
                            )
                        )
                    }
                    else {
                        val temaId = repo.agregarTema(cursoId, request.tema)
                        val temaCreado = repo.obtenerTema(cursoId, temaId)

                        call.respond(
                            HttpStatusCode.Created,
                            TemaResponse(
                                message = "Tema creado sin explicación. Debes agregar una explicación antes de generar preguntas.",
                                temaId = temaId,
                                tema = temaCreado!!,
                                requiereValidacion = false
                            )
                        )
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al crear tema: ${e.message}")
                    )
                }
            }

            put("{id}/temas/{temaId}") {
                val cursoId = call.parameters["id"] ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )
                val temaId = call.parameters["temaId"] ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del tema")
                )

                try {
                    val tema = call.receive<Tema>()
                    repo.actualizarTema(cursoId, temaId, tema)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Tema actualizado correctamente")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al actualizar tema: ${e.message}")
                    )
                }
            }

            delete("{id}/temas/{temaId}") {
                val cursoId = call.parameters["id"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )
                val temaId = call.parameters["temaId"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del tema")
                )

                try {
                    repo.eliminarTema(cursoId, temaId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Tema eliminado correctamente")
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al eliminar tema: ${e.message}")
                    )
                }
            }

            post("{id}/temas/{temaId}/generar-explicacion") {
                val cursoId = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )
                val temaId = call.parameters["temaId"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del tema")
                )

                try {
                    if (iaService == null) {
                        return@post call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf("error" to "Servicio de IA no disponible. Verifica tu OPENAI_API_KEY en .env")
                        )
                    }

                    val tema = repo.obtenerTema(cursoId, temaId)
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Tema no encontrado")
                        )

                    val request = try {
                        call.receive<GenerarExplicacionRequest>()
                    } catch (e: Exception) {
                        null
                    }

                    val explicacionGenerada = iaService.generarExplicacion(
                        cursoId = cursoId,
                        temaId = temaId,
                        tituloTema = request?.tituloTema ?: tema.titulo,
                        contenidoTema = request?.contenidoTema ?: tema.contenido
                    )

                    explicacionRepo.actualizarExplicacion(
                        cursoId = cursoId,
                        temaId = temaId,
                        explicacion = explicacionGenerada,
                        fuente = "ia",
                        estado = "pendiente"
                    )

                    val temaActualizado = repo.obtenerTema(cursoId, temaId)

                    call.respond(
                        HttpStatusCode.OK,
                        ExplicacionResponse(
                            message = "Explicación generada correctamente con IA. Pendiente de validación.",
                            explicacion = explicacionGenerada,
                            fuente = "ia",
                            tema = temaActualizado!!
                        )
                    )

                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al generar explicación: ${e.message}")
                    )
                }
            }

            put("{id}/temas/{temaId}/validar-explicacion") {
                val cursoId = call.parameters["id"] ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )
                val temaId = call.parameters["temaId"] ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del tema")
                )

                try {
                    val data = call.receive<Map<String, String>>()
                    val nuevoEstado = data["estado"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Falta el campo 'estado'")
                        )

                    if (nuevoEstado !in listOf("aprobada", "rechazada", "pendiente")) {
                        return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Estado inválido. Debe ser 'aprobada', 'rechazada' o 'pendiente'")
                        )
                    }

                    explicacionRepo.actualizarEstadoExplicacion(cursoId, temaId, nuevoEstado)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "message" to "Explicación validada correctamente",
                            "nuevoEstado" to nuevoEstado
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al validar explicación: ${e.message}")
                    )
                }
            }

            get("{id}/temas/{temaId}/explicacion-valida") {
                val cursoId = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )
                val temaId = call.parameters["temaId"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del tema")
                )

                try {
                    val tieneExplicacionAprobada = explicacionRepo.tieneExplicacionAprobada(cursoId, temaId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "tieneExplicacionAprobada" to tieneExplicacionAprobada,
                            "mensaje" to if (tieneExplicacionAprobada) {
                                "El tema tiene una explicación aprobada"
                            } else {
                                "El tema no tiene una explicación aprobada. Debe generarse y validarse antes de crear un quiz."
                            }
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al verificar explicación: ${e.message}")
                    )
                }
            }

            get("/solicitudes-preguntas") {
                try {
                    val usuario = call.getUserFromToken()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Token inválido o expirado")
                        )

                    if (usuario.rol != "docente") {
                        return@get call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "Solo los docentes pueden ver las solicitudes")
                        )
                    }

                    val solicitudes = solicitudPreguntasRepo.obtenerSolicitudesPorDocente(usuario.uid)
                    
                    call.respond(HttpStatusCode.OK, solicitudes)

                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener solicitudes: ${e.message}")
                    )
                }
            }

            get("/solicitudes-preguntas/resumen") {
                try {
                    val usuario = call.getUserFromToken()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Token inválido o expirado")
                        )

                    if (usuario.rol != "docente") {
                        return@get call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "Solo los docentes pueden ver el resumen")
                        )
                    }

                    val resumen = solicitudPreguntasRepo.obtenerResumenSolicitudesPorDocente(usuario.uid)
                    
                    call.respond(HttpStatusCode.OK, resumen)

                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener resumen: ${e.message}")
                    )
                }
            }

            get("{id}/solicitudes-preguntas") {
                try {
                    val cursoId = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Falta ID del curso")
                        )

                    val usuario = call.getUserFromToken()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Token inválido o expirado")
                        )

                    if (usuario.rol != "docente") {
                        return@get call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "Solo los docentes pueden ver las solicitudes")
                        )
                    }

                    val curso = repo.obtenerCursoPorId(cursoId)
                    if (curso?.docenteId != usuario.uid) {
                        return@get call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "No tienes permiso para ver las solicitudes de este curso")
                        )
                    }

                    val todasSolicitudes = solicitudPreguntasRepo.obtenerSolicitudesPorDocente(usuario.uid)
                    val solicitudesDelCurso = todasSolicitudes.filter { it.cursoId == cursoId }
                    
                    call.respond(HttpStatusCode.OK, solicitudesDelCurso)

                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener solicitudes: ${e.message}")
                    )
                }
            }

            get("{id}/temas/{temaId}/solicitudes-preguntas") {
                try {
                    val cursoId = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Falta ID del curso")
                        )
                    
                    val temaId = call.parameters["temaId"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Falta ID del tema")
                        )

                    val usuario = call.getUserFromToken()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Token inválido o expirado")
                        )

                    if (usuario.rol != "docente") {
                        return@get call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "Solo los docentes pueden ver las solicitudes")
                        )
                    }

                    val todasSolicitudes = solicitudPreguntasRepo.obtenerSolicitudesPorDocente(usuario.uid)
                    val solicitudesDelTema = todasSolicitudes.filter { 
                        it.cursoId == cursoId && it.temaId == temaId 
                    }
                    
                    call.respond(HttpStatusCode.OK, solicitudesDelTema)

                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener solicitudes: ${e.message}")
                    )
                }
            }

            put("/solicitudes-preguntas/{solicitudId}/atender") {
                try {
                    val solicitudId = call.parameters["solicitudId"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Falta ID de la solicitud")
                        )

                    val usuario = call.getUserFromToken()
                        ?: return@put call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Token inválido o expirado")
                        )

                    if (usuario.rol != "docente") {
                        return@put call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "Solo los docentes pueden atender solicitudes")
                        )
                    }

                    val solicitud = solicitudPreguntasRepo.obtenerSolicitudPorId(solicitudId)
                    if (solicitud == null) {
                        return@put call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Solicitud no encontrada")
                        )
                    }

                    val curso = repo.obtenerCursoPorId(solicitud.cursoId)
                    if (curso?.docenteId != usuario.uid) {
                        return@put call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "No tienes permiso para atender esta solicitud")
                        )
                    }

                    solicitudPreguntasRepo.marcarAtendida(solicitudId)
                    
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Solicitud marcada como atendida")
                    )

                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al atender solicitud: ${e.message}")
                    )
                }
            }

            put("{id}/temas/{temaId}/solicitudes-preguntas/atender-todas") {
                try {
                    val cursoId = call.parameters["id"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Falta ID del curso")
                        )
                    
                    val temaId = call.parameters["temaId"]
                        ?: return@put call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Falta ID del tema")
                        )

                    val usuario = call.getUserFromToken()
                        ?: return@put call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Token inválido o expirado")
                        )

                    if (usuario.rol != "docente") {
                        return@put call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "Solo los docentes pueden atender solicitudes")
                        )
                    }

                    val curso = repo.obtenerCursoPorId(cursoId)
                    if (curso?.docenteId != usuario.uid) {
                        return@put call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "No tienes permiso para atender estas solicitudes")
                        )
                    }

                    solicitudPreguntasRepo.marcarAtendidasPorTema(cursoId, temaId)
                    
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Todas las solicitudes del tema han sido marcadas como atendidas")
                    )

                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al atender solicitudes: ${e.message}")
                    )
                }
            }

            get("{id}/temas/{temaId}/solicitudes-preguntas/contador") {
                try {
                    val cursoId = call.parameters["id"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Falta ID del curso")
                        )
                    
                    val temaId = call.parameters["temaId"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Falta ID del tema")
                        )

                    val usuario = call.getUserFromToken()
                        ?: return@get call.respond(
                            HttpStatusCode.Unauthorized,
                            mapOf("error" to "Token inválido o expirado")
                        )

                    if (usuario.rol != "docente") {
                        return@get call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf("error" to "Solo los docentes pueden ver el contador")
                        )
                    }

                    val contador = solicitudPreguntasRepo.contarSolicitudesPorTema(cursoId, temaId)
                    
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "cursoId" to cursoId,
                            "temaId" to temaId,
                            "solicitudesPendientes" to contador
                        )
                    )

                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener contador: ${e.message}")
                    )
                }
            }
        }
    }
}