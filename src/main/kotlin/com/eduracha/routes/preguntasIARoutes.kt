package com.eduracha.routes

import com.eduracha.repository.PreguntaRepository
import com.eduracha.services.OpenAIService
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
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
    val preguntas: List<com.eduracha.models.Pregunta>
)

fun Application.preguntasIARoutes() {
    val dotenv = dotenv()
    val openAiKey = dotenv["OPENAI_API_KEY"] ?: error("Falta la API Key de OpenAI")
    val client = HttpClient(CIO)
    val iaService = OpenAIService(client, openAiKey)
    val repo = PreguntaRepository()

    routing {
        route("/api/ia/preguntas") {

            // Generar preguntas con IA y guardarlas en la base de datos
            post("/generar") {
                try {
                    val data = call.receive<Map<String, String>>()

                    val cursoId = data["cursoId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Falta cursoId")
                    val temaId = data["temaId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Falta temaId")
                    val temaTexto = data["temaTexto"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Falta temaTexto")
                    val cantidad = data["cantidad"]?.toIntOrNull() ?: 5

                    val preguntas = iaService.generarYGuardarPreguntas(cursoId, temaId, temaTexto, cantidad)

                    // Respuesta corregida usando clase serializable
                    call.respond(
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
                        mapOf("error" to (e.message ?: "Error interno del servidor"))
                    )
                }
            }

            // Obtener preguntas pendientes generadas por IA
            get("/pendientes/{cursoId}/{temaId}") {
                val cursoId = call.parameters["cursoId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Falta cursoId")
                val temaId = call.parameters["temaId"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Falta temaId")

                try {
                    val preguntasPendientes = repo.obtenerPreguntasPendientes(cursoId, temaId)
                    call.respond(preguntasPendientes)
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // Aprobar o rechazar una pregunta generada por IA
            put("/revisar/{id}") {
                val id = call.parameters["id"]
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Falta ID")

                try {
                    val datosRevision = call.receive<Map<String, String>>()
                    val estado = datosRevision["estado"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Falta estado")
                    val notas = datosRevision["notas"] ?: ""

                    repo.revisarPreguntaIA(id, estado, notas)
                    call.respond(mapOf("message" to "Pregunta revisada correctamente", "estado" to estado))
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // Eliminar una pregunta generada por IA
            delete("/{id}") {
                val id = call.parameters["id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Falta ID")

                try {
                    repo.eliminarPregunta(id)
                    call.respond(mapOf("message" to "Pregunta eliminada correctamente"))
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }
        }
    }
}
