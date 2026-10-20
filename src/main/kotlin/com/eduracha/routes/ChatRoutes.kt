package com.eduracha.routes

import com.eduracha.ai.AiService
import com.eduracha.utils.getUserFromToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.chatRoutes(aiService: AiService? = null) {
    routing {
        route("/api/chat") {
            post {
                // Requiere autenticación para evitar abuso/costos
                val user = call.getUserFromToken()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token requerido"))
                val data = try { call.receive<Map<String, String>>() } catch (_: Exception) { emptyMap() }
                val prompt = data["prompt"]?.trim().orEmpty()
                if (prompt.isBlank() || prompt.length > 4000) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Prompt inválido (1-4000 caracteres)"))
                }
                if (aiService == null) {
                    return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "IA no configurada"))
                }
                try {
                    val resp = aiService.chatEducativo(prompt)
                    call.respond(mapOf("respuesta" to resp))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Error de IA: ${e.message}"))
                }
            }
        }
    }
}
