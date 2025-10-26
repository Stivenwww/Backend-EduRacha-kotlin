package com.eduracha.routes

import com.eduracha.models.Curso
import com.eduracha.models.Tema
import com.eduracha.repository.CursoRepository
import com.eduracha.repository.ExplicacionRepository
import com.eduracha.services.OpenAIService
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
    
    // Configurar servicio de IA
    val dotenv = dotenv()
    val openAiKey = dotenv["OPENAI_API_KEY"]
    val client = HttpClient(CIO)
    val iaService = if (!openAiKey.isNullOrEmpty()) {
        OpenAIService(client, openAiKey)
    } else null

    routing {
        route("/api/cursos") {

            // CRUD BÁSICO DE CURSOS
            
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


            // GESTIÓN DE TEMAS CON EXPLICACIONES

            // GET /api/cursos/{id}/temas
            // Obtener todos los temas de un curso
            get("{id}/temas") {
                val cursoId = call.parameters["id"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )

                // Filtro opcional por estado de explicación
                val estado = call.request.queryParameters["estado"]

                try {
                    val temas = if (!estado.isNullOrBlank()) {
                        explicacionRepo.obtenerTemasPorEstadoExplicacion(cursoId, estado)
                    } else {
                        repo.obtenerTemasPorCurso(cursoId)
                    }
                    
                    call.respond(HttpStatusCode.OK, temas)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al obtener temas: ${e.message}")
                    )
                }
            }

            // GET /api/cursos/{id}/temas/{temaId}
            // Obtener un tema específico con su explicación
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

            // POST /api/cursos/{id}/temas/simple
            // Agregar tema sin explicación (para agregar explicación después)
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

            // POST /api/cursos/{id}/temas
            // OPCIÓN A: Tema con explicación manual (aprobada automáticamente)
            // OPCIÓN B: Tema con explicación IA (pendiente de validación)
            post("{id}/temas") {
                val cursoId = call.parameters["id"] ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )

                try {
                    val request = call.receive<TemaConExplicacionRequest>()

                    // OPCIÓN A: Docente proporciona explicación manual
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
                    // OPCIÓN B: Generar explicación con IA
                    else if (request.generarConIA) {
                        if (iaService == null) {
                            return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                mapOf("error" to "Servicio de IA no disponible. Verifica OPENAI_API_KEY en .env")
                            )
                        }

                        // 1. Crear el tema primero
                        val temaId = repo.agregarTema(cursoId, request.tema)

                        // 2. Generar explicación con IA
                        val explicacionGenerada = iaService.generarExplicacion(
                            tituloTema = request.tema.titulo,
                            contenidoTema = request.tema.contenido
                        )

                        // 3. Actualizar el tema con la explicación generada (estado: pendiente)
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
                    // Sin explicación
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

            // PUT /api/cursos/{id}/temas/{temaId}
            // Actualizar tema completo
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

            // DELETE /api/cursos/{id}/temas/{temaId}
            // Eliminar tema
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

           
            // GESTIÓN DE EXPLICACIONES DE TEMASS

            // POST /api/cursos/{id}/temas/{temaId}/generar-explicacion
            // Generar explicación con IA para un tema existente
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
                            mapOf("error" to "Servicio de IA no disponible")
                        )
                    }

                    // Obtener el tema
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

                    // Generar la explicación con IA
                    val explicacion = iaService.generarExplicacion(
                        tituloTema = request?.tituloTema ?: tema.titulo,
                        contenidoTema = request?.contenidoTema ?: tema.contenido
                    )

                    // Guardar la explicación generada con estado "pendiente"
                    explicacionRepo.actualizarExplicacion(
                        cursoId = cursoId,
                        temaId = temaId,
                        explicacion = explicacion,
                        fuente = "ia",
                        estado = "pendiente"
                    )

                    // Obtener el tema actualizado
                    val temaActualizado = repo.obtenerTema(cursoId, temaId)

                    call.respond(
                        HttpStatusCode.OK,
                        ExplicacionResponse(
                            message = "Explicación generada correctamente con IA. Pendiente de validación.",
                            explicacion = explicacion,
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

            // PUT /api/cursos/{id}/temas/{temaId}/explicacion
            // Actualizar explicación manualmente (docente)
            put("{id}/temas/{temaId}/explicacion") {
                val cursoId = call.parameters["id"] ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del curso")
                )
                val temaId = call.parameters["temaId"] ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Falta ID del tema")
                )

                try {
                    val request = call.receive<ActualizarExplicacionRequest>()

                    // Verificar que el tema existe
                    val tema = repo.obtenerTema(cursoId, temaId)
                        ?: return@put call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Tema no encontrado")
                        )

                    // Actualizar con explicación manual (ya aprobada)
                    explicacionRepo.actualizarExplicacion(
                        cursoId = cursoId,
                        temaId = temaId,
                        explicacion = request.explicacion,
                        fuente = request.fuente,
                        estado = "aprobada" // Las explicaciones manuales del docente se aprueban automáticamente
                    )

                    val temaActualizado = repo.obtenerTema(cursoId, temaId)

                    call.respond(
                        HttpStatusCode.OK,
                        ExplicacionResponse(
                            message = "Explicación actualizada correctamente",
                            explicacion = request.explicacion,
                            fuente = request.fuente,
                            tema = temaActualizado!!
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Error al actualizar explicación: ${e.message}")
                    )
                }
            }

            // PUT /api/cursos/{id}/temas/{temaId}/validar-explicacion
            // Validar (aprobar/rechazar) explicación generada por IA
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

            // GET /api/cursos/{id}/temas/{temaId}/explicacion-valida
            // Verificar si un tema tiene explicación aprobada
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
        }
    }
}