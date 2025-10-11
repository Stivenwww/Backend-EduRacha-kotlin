package com.eduracha.routes

import com.eduracha.utils.OpenAIClient
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.chatRoutes() {
    routing {
        route("/api/chat") {
            post {
                val data = call.receive<Map<String, String>>()
                val prompt = data["prompt"] ?: return@post call.respondText("Falta el prompt")
                val respuesta = OpenAIClient.generarRespuesta(prompt)
                call.respond(mapOf("respuesta" to respuesta))
            }
        }
    }
}
