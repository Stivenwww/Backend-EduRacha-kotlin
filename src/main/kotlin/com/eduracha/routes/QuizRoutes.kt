package com.eduracha.routes

import com.eduracha.services.QuizService
import com.eduracha.models.*
import com.eduracha.utils.getUserFromToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.quizRoutes(quizService: QuizService) {
    
    route("/quiz") {
        
        // POST /quiz/iniciar - Iniciar un nuevo quiz
        post("/iniciar") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized, 
                        mapOf("error" to "Token inválido o expirado")
                    )

                val request = call.receive<IniciarQuizRequest>()
                
                val response = quizService.iniciarQuiz(
                    cursoId = request.cursoId,
                    temaId = request.temaId,
                    userId = usuario.uid
                )
                
                call.respond(HttpStatusCode.OK, response)
                
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError, 
                    mapOf("error" to "Error al iniciar quiz: ${e.message}")
                )
            }
        }
        
        // POST /quiz/finalizar - Finalizar un quiz y obtener resultados
        post("/finalizar") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized, 
                        mapOf("error" to "Token inválido o expirado")
                    )

                val request = call.receive<FinalizarQuizRequest>()
                
                val response = quizService.finalizarQuiz(
                    quizId = request.quizId,
                    respuestas = request.respuestas,
                    userId = usuario.uid
                )
                
                call.respond(HttpStatusCode.OK, response)
                
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError, 
                    mapOf("error" to "Error al finalizar quiz: ${e.message}")
                )
            }
        }
        
        // GET /quiz/{quizId}/revision - Obtener revisión del quiz
        get("/{quizId}/revision") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized, 
                        mapOf("error" to "Token inválido o expirado")
                    )

                val quizId = call.parameters["quizId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest, 
                        mapOf("error" to "Quiz ID es requerido")
                    )
                
                val response = quizService.obtenerRevision(quizId, usuario.uid)
                
                call.respond(HttpStatusCode.OK, response)
                
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError, 
                    mapOf("error" to "Error al obtener revisión: ${e.message}")
                )
            }
        }
        
        // POST /quiz/explicacion-vista - Marcar explicación como vista
        post("/explicacion-vista") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized, 
                        mapOf("error" to "Token inválido o expirado")
                    )

                val request = call.receive<Map<String, String>>()
                val temaId = request["temaId"]
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest, 
                        mapOf("error" to "temaId es requerido")
                    )
                
                quizService.marcarExplicacionVista(usuario.uid, temaId)
                
                call.respond(
                    HttpStatusCode.OK, 
                    mapOf("message" to "Explicación marcada como vista")
                )
                
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError, 
                    mapOf("error" to "Error al marcar explicación: ${e.message}")
                )
            }
        }
        
        // GET /quiz/vidas/{cursoId} - Obtener vidas disponibles
        get("/vidas/{cursoId}") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized, 
                        mapOf("error" to "Token inválido o expirado")
                    )

                val cursoId = call.parameters["cursoId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest, 
                        mapOf("error" to "Curso ID es requerido")
                    )
                
                val vidas = quizService.obtenerVidas(cursoId, usuario.uid)
                
                call.respond(HttpStatusCode.OK, vidas)
                
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError, 
                    mapOf("error" to "Error al obtener vidas: ${e.message}")
                )
            }
        }
        
        // GET /quiz/{quizId}/retroalimentacion - Obtener retroalimentación de preguntas falladas
        get("/{quizId}/retroalimentacion") {
            try {
                val usuario = call.getUserFromToken()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized, 
                        mapOf("error" to "Token inválido o expirado")
                    )

                val quizId = call.parameters["quizId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest, 
                        mapOf("error" to "Quiz ID es requerido")
                    )
                
                val response = quizService.obtenerRetroalimentacionFallos(quizId, usuario.uid)
                
                call.respond(HttpStatusCode.OK, response)
                
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError, 
                    mapOf("error" to "Error al obtener retroalimentación: ${e.message}")
                )
            }
        }
    }
}