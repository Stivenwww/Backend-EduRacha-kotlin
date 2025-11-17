package com.eduracha.routes

import com.eduracha.services.QuizService
import com.eduracha.models.*
import com.eduracha.utils.getUserFromToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.eduracha.repository.QuizRepository 
import com.eduracha.repository.CursoRepository
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*



fun Route.quizRoutes(
    quizService: QuizService,
    quizRepo: QuizRepository, 
    cursoRepo: CursoRepository 
) {
    
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
    // Verificar modos disponibles para un tema
get("/api/quiz/modos-disponibles") {
    val cursoId = call.parameters["cursoId"] ?: return@get call.respond(
        HttpStatusCode.BadRequest, "Falta cursoId"
    )
    val temaId = call.parameters["temaId"] ?: return@get call.respond(
        HttpStatusCode.BadRequest, "Falta temaId"
    )
    val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("uid")?.asString()
        ?: return@get call.respond(HttpStatusCode.Unauthorized, "No autenticado")
    
    val estadoTema = quizService.obtenerEstadoTema(cursoId, temaId, userId)
    val temaAprobado = estadoTema?.aprobado ?: false
    
    val modosDisponibles = if (temaAprobado) {
        listOf("oficial", "practica")
    } else {
        listOf("oficial")
    }
    
    call.respond(ModoQuizDisponibleResponse(
        temaId = temaId,
        temaAprobado = temaAprobado,
        modosDisponibles = modosDisponibles,
        modoRecomendado = if (temaAprobado) "practica" else "oficial",
        mensaje = if (temaAprobado) {
            "Ya aprobaste este tema. Puedes practicar o hacer un quiz oficial."
        } else {
            "Debes aprobar este tema primero"
        }
    ))
}
// Verificar disponibilidad del quiz final
get("/api/quiz/final/disponible") {
    val cursoId = call.parameters["cursoId"] ?: return@get call.respond(
        HttpStatusCode.BadRequest, "Falta cursoId"
    )

    val usuario = call.getUserFromToken()
        ?: return@get call.respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Token inválido o expirado")
        )

    val userId = usuario.uid

    val perfilCurso = quizRepo.obtenerPerfilCurso(userId, cursoId)
    val curso = cursoRepo.obtenerCursoPorId(cursoId)

    val temasAprobados = perfilCurso?.temasCompletados?.values?.count { it.aprobado } ?: 0
    val totalTemas = curso?.temas?.size ?: 0
    val disponible = temasAprobados == totalTemas && totalTemas > 0

    call.respond(
        QuizFinalDisponibleResponse(
            disponible = disponible,
            temasAprobados = temasAprobados,
            totalTemas = totalTemas,
            mensaje = if (disponible) {
                "¡Felicitaciones! Puedes realizar el quiz final del curso"
            } else {
                "Progreso: $temasAprobados/$totalTemas temas aprobados"
            }
        )
    )
}

// Iniciar quiz final
post("/api/quiz/final/iniciar") {
    val cursoId = call.receive<Map<String, String>>()["cursoId"]
        ?: return@post call.respond(HttpStatusCode.BadRequest, "Falta cursoId")

    // usar Firebase Token en vez de JWT local
    val usuario = call.getUserFromToken()
        ?: return@post call.respond(
            HttpStatusCode.Unauthorized,
            mapOf("error" to "Token inválido o expirado")
        )

    val userId = usuario.uid

    try {
        val response = quizService.iniciarQuizFinal(cursoId, userId)
        call.respond(HttpStatusCode.OK, response)
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
    }
}
}