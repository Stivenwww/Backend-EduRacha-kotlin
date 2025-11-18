package com.eduracha.routes

import com.eduracha.models.Pregunta
import com.eduracha.repository.PreguntaRepository
import com.eduracha.repository.ExplicacionRepository
import com.eduracha.services.OpenAIService
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.github.cdimascio.dotenv.dotenv
import kotlinx.serialization.Serializable

@Serializable
data class PreguntasIAResponse(
    val message: String,
    val total: Int,
    val preguntas: List<Pregunta>
)

fun Application.preguntasRoutes() {
    val repo = PreguntaRepository()
    val explicacionRepo = ExplicacionRepository()

    routing {

        // RUTAS CRUD DE PREGUNTAS
        route("/api/preguntas") {

            // GET /api/preguntas?cursoId=...&estado=...
            get {
                val cursoId = call.request.queryParameters["cursoId"]
                val estado = call.request.queryParameters["estado"]

                try {
                    val preguntas = if (!cursoId.isNullOrEmpty()) {
                        repo.obtenerPreguntasPorCursoYEstado(cursoId, estado)
                    } else {
                        repo.obtenerPreguntas()
                    }

                    call.respond(HttpStatusCode.OK, preguntas)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Error interno del servidor"))
                    )
                }
            }

            // GET /api/preguntas/{id}
            get("/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                try {
                    val pregunta = repo.obtenerPreguntaPorId(id)
                    if (pregunta != null) call.respond(HttpStatusCode.OK, pregunta)
                    else call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pregunta no encontrada"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // POST /api/preguntas
            post {
                try {
                    val pregunta = call.receive<Pregunta>()
                    
                    // VALIDACIÓN: Verificar que existe explicación aprobada para el tema
                    if (!pregunta.temaId.isNullOrBlank() && !pregunta.cursoId.isNullOrBlank()) {
                        val tieneExplicacion = explicacionRepo.tieneExplicacionAprobada(
                            pregunta.cursoId, 
                            pregunta.temaId
                        )
                        
                        if (!tieneExplicacion) {
                            return@post call.respond(
                                HttpStatusCode.PreconditionFailed,
                                mapOf(
                                    "error" to "No se puede crear la pregunta. El tema no tiene una explicación aprobada.",
                                    "mensaje" to "Primero debe generar y aprobar una explicación para este tema."
                                )
                            )
                        }
                    }
                    
                    val id = repo.crearPregunta(pregunta)
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf("message" to "Pregunta creada correctamente", "id" to id)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (e.message ?: "Error al crear la pregunta"))
                    )
                }
            }

            // PUT /api/preguntas/{id}
            put("/{id}") {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                try {
                    val pregunta = call.receive<Pregunta>()
                    repo.actualizarPregunta(id, pregunta)
                    call.respond(mapOf("message" to "Pregunta actualizada correctamente"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // DELETE /api/preguntas/{id}
            delete("/{id}") {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                try {
                    repo.eliminarPregunta(id)
                    call.respond(mapOf("message" to "Pregunta eliminada correctamente"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // PUT /api/preguntas/{id}/estado
            put("/{id}/estado") {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                try {
                    val data = call.receive<Map<String, String>>()
                    val estado = data["estado"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val notas = data["notas"]
                    repo.actualizarEstadoPregunta(id, estado, notas)
                    call.respond(mapOf("message" to "Estado actualizado", "nuevoEstado" to estado))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // DELETE /api/preguntas/cache
            delete("/cache") {
                repo.limpiarCacheFirebase()
                call.respond(mapOf("message" to "Caché de Firebase limpiada"))
            }
        }

        // RUTAS DE IA (Generación Automática)
        route("/api/preguntas/ia") {
          /*   val dotenv = dotenv()
            val openAiKey = dotenv["OPENAI_API_KEY"] ?: error("Falta la API Key de OpenAI")
            val client = HttpClient(CIO)
            val iaService = OpenAIService(client, openAiKey)*/


            
            // Primero intenta leer la variable real del entorno (Sevella)
            val openAiKey = System.getenv("OPENAI_API_KEY")
                ?: dotenv()["OPENAI_API_KEY"]
                ?: error("Falta la API Key de OpenAI")

            val client = HttpClient(CIO)
            val iaService = OpenAIService(client, openAiKey)


            

            // POST /api/preguntas/ia/generar
            post("/generar") {
                try {
                    val data = call.receive<Map<String, String>>()
                    val cursoId = data["cursoId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Falta cursoId")
                    val temaId = data["temaId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Falta temaId")
                    val temaTexto = data["temaTexto"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Falta temaTexto")
                    val cantidad = data["cantidad"]?.toIntOrNull() ?: 5

                    // VALIDACIÓN CRÍTICA: Verificar que existe explicación aprobada
                    val tieneExplicacion = explicacionRepo.tieneExplicacionAprobada(cursoId, temaId)
                    
                    if (!tieneExplicacion) {
                        return@post call.respond(
                            HttpStatusCode.PreconditionFailed,
                            mapOf(
                                "error" to "No se pueden generar preguntas. El tema no tiene una explicación aprobada.",
                                "mensaje" to "FLUJO REQUERIDO: 1) Generar explicación, 2) Validar explicación, 3) Generar preguntas",
                                "accionRequerida" to "Generar y aprobar explicación del tema"
                            )
                        )
                    }

                    val preguntas = iaService.generarYGuardarPreguntas(cursoId, temaId, temaTexto, cantidad)

                    call.respond(
                        HttpStatusCode.OK,
                        PreguntasIAResponse(
                            message = "Preguntas generadas correctamente",
                            total = preguntas.size,
                            preguntas = preguntas
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Error generando preguntas con IA"))
                    )
                }
            }
        }
    }
}