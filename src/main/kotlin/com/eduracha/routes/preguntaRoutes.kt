package com.eduracha.routes

import com.eduracha.models.Pregunta
import com.eduracha.repository.PreguntaRepository
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*

fun Application.preguntaRoutes() {
    val repo = PreguntaRepository()

    routing {
        route("/api/preguntas") {

            // Crear una nueva pregunta
            post {
                try {
                    val pregunta = call.receive<Pregunta>()
                    val id = repo.crearPregunta(pregunta)
                    call.respond(mapOf("message" to "Pregunta creada exitosamente", "id" to id))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // Obtener todas las preguntas
            get {
                try {
                    val preguntas = repo.obtenerPreguntas()
                    call.respond(preguntas)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // Obtener una pregunta por ID
            get("{id}") {
                val id = call.parameters["id"]
                    ?: return@get call.respondText("Falta ID", status = HttpStatusCode.BadRequest)

                try {
                    val pregunta = repo.obtenerPreguntaPorId(id)
                    if (pregunta == null) {
                        call.respondText("Pregunta no encontrada", status = HttpStatusCode.NotFound)
                    } else {
                        call.respond(pregunta)
                    }
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // Actualizar una pregunta
            put("{id}") {
                val id = call.parameters["id"]
                    ?: return@put call.respondText("Falta ID", status = HttpStatusCode.BadRequest)

                try {
                    val pregunta = call.receive<Pregunta>()
                    repo.actualizarPregunta(id, pregunta)
                    call.respond(mapOf("message" to "Pregunta actualizada correctamente"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // Eliminar una pregunta
            delete("{id}") {
                val id = call.parameters["id"]
                    ?: return@delete call.respondText("Falta ID", status = HttpStatusCode.BadRequest)

                try {
                    repo.eliminarPregunta(id)
                    call.respond(mapOf("message" to "Pregunta eliminada correctamente"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }
        }
    }
}
