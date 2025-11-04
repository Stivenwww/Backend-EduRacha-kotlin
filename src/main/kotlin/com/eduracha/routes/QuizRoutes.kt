package com.eduracha.routes

import com.eduracha.models.*
import com.eduracha.repository.QuizRepository
import com.eduracha.repository.PreguntaRepository
import com.eduracha.services.QuizService
import com.google.firebase.auth.FirebaseAuth
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.eduracha.repository.RachaRepository



fun Route.quizRoutes() {

    val quizRepo = QuizRepository()
    val preguntaRepo = PreguntaRepository()
    val quizService = QuizService(quizRepo, preguntaRepo)

    
    
    // POST /quiz/explicacion/marcar-vista
    post("/explicacion/marcar-vista") {
        try {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

            val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
            val userId = decodedToken.uid

            val request = call.receive<Map<String, String>>()
            val temaId = request["temaId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "temaId es requerido"))

            quizService.marcarExplicacionVista(userId, temaId)

            call.respond(HttpStatusCode.OK, mapOf("message" to "Explicación marcada como vista"))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // POST /quiz/iniciar
    post("/iniciar") {
        try {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

            val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
            val userId = decodedToken.uid

            val request = call.receive<IniciarQuizRequest>()

            val response = quizService.iniciarQuiz(request.cursoId, request.temaId, userId)

            call.respond(HttpStatusCode.Created, response)

        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // POST /quiz/finalizar
    post("/finalizar") {
        try {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

            val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
            val userId = decodedToken.uid

            val request = call.receive<FinalizarQuizRequest>()

            val response = quizService.finalizarQuiz(request.quizId, request.respuestas, userId)

            call.respond(HttpStatusCode.OK, response)

        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // GET /quiz/revision/{quizId}
    get("/revision/{quizId}") {
        try {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

            val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
            val userId = decodedToken.uid

            val quizId = call.parameters["quizId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "quizId es requerido"))

            val response = quizService.obtenerRevision(quizId, userId)

            call.respond(HttpStatusCode.OK, response)

        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // GET /quiz/curso/{cursoId}/vidas
    get("/curso/{cursoId}/vidas") {
        try {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

            val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
            val userId = decodedToken.uid

            val cursoId = call.parameters["cursoId"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "cursoId es requerido"))

            val vidas = quizService.obtenerVidas(cursoId, userId)

            call.respond(HttpStatusCode.OK, vidas)

        } catch (e: IllegalStateException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // GET /quiz/historial
    get("/historial") {
        try {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

            val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
            val userId = decodedToken.uid

            val cursoId = call.request.queryParameters["cursoId"]

            val quizzes = quizRepo.obtenerQuizzesPorEstudiante(userId, cursoId)

            call.respond(HttpStatusCode.OK, mapOf("quizzes" to quizzes))

        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }
// GET /quiz/curso/{cursoId}/tema/{temaId}/info
get("/curso/{cursoId}/tema/{temaId}/info") {
    try {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

        val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
        val userId = decodedToken.uid

        val cursoId = call.parameters["cursoId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "cursoId es requerido"))

        val temaId = call.parameters["temaId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "temaId es requerido"))

        // Obtener tema
        val tema = quizRepo.verificarTemaEnCurso(cursoId, temaId)
        if (!tema) {
            return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Tema no encontrado"))
        }

        // Obtener inscripción y vidas
        val inscripcion = quizRepo.obtenerInscripcion(cursoId, userId)
        val vidas = if (inscripcion != null) {
            val inscripcionActualizada = quizRepo.regenerarVidas(
                cursoId,
                inscripcion,
                QuizService.VIDA_REGEN_MINUTOS
            )
            if (inscripcionActualizada.vidasActuales != inscripcion.vidasActuales) {
                quizRepo.actualizarInscripcion(cursoId, inscripcionActualizada)
            }
            inscripcionActualizada.vidasActuales
        } else {
            0
        }

        // Contar preguntas disponibles
        val preguntas = preguntaRepo.obtenerPreguntasPorCursoYEstado(cursoId, "aprobada")
            .filter { it.temaId == temaId }

        // Verificar si vio la explicación
        val vioExplicacion = quizRepo.verificarExplicacionVista(userId, temaId)

        // Enviar respuesta con DTO seguro
        val response = TemaInfoResponse(
            temaId = temaId,
            cursoId = cursoId,
            preguntasDisponibles = preguntas.size,
            vidasActuales = vidas,
            explicacionVista = vioExplicacion,
            inscrito = (inscripcion != null)
        )

        call.respond(HttpStatusCode.OK, response)

    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error interno del servidor")))
    }
}

        // GET /quiz/{quizId}/retroalimentacion
get("/{quizId}/retroalimentacion") {
    try {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

        val decodedToken = FirebaseAuth.getInstance().verifyIdToken(token)
        val userId = decodedToken.uid

        val quizId = call.parameters["quizId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "quizId es requerido"))

        // Lógica en el servicio
        val response = quizService.obtenerRetroalimentacionFallos(quizId, userId)

        call.respond(HttpStatusCode.OK, response)

    } catch (e: IllegalStateException) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
    }
}

// GET /quiz/curso/{cursoId}/ranking?filtro=experiencia  /ranking?filtro=racha   /ranking?filtro=vidas
get("/curso/{cursoId}/ranking") {
    try {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token no proporcionado"))

        FirebaseAuth.getInstance().verifyIdToken(token)

        val cursoId = call.parameters["cursoId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "cursoId es requerido"))

        // Filtro (por defecto experiencia)
        val filtro = call.request.queryParameters["filtro"] ?: "experiencia"

        val rachaRepo = RachaRepository()
        val ranking = rachaRepo.obtenerRankingPorCurso(cursoId, filtro)

        call.respond(HttpStatusCode.OK, mapOf("ranking" to ranking))

    } catch (e: Exception) {
        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Error interno")))
    }
}
}

