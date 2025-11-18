package com.eduracha

import com.eduracha.routes.*
import com.eduracha.utils.FirebaseInit
import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import com.eduracha.repository.*
import com.eduracha.services.*
import com.eduracha.jobs.CronJobReportes
import com.eduracha.utils.getUserFromToken
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.auth.oauth2.GoogleCredentials
import java.util.Base64

fun main() {
    embeddedServer(
        Netty,
        port = System.getenv("PORT")?.toInt() ?: 8080,
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {

    // Cargar dotenv solo en desarrollo
    val dotenv = try {
        dotenv { ignoreIfMissing = true }
    } catch (e: Exception) {
        
    }
    fun getEnv(key: String): String? =
        System.getenv(key)


    val firebaseUrl = getEnv("FIREBASE_DATABASE_URL")
        ?: throw IllegalStateException("FIREBASE_DATABASE_URL no encontrada")

    val base64 = getEnv("FIREBASE_CREDENTIALS_BASE64")
        ?: throw IllegalStateException("FIREBASE_CREDENTIALS_BASE64 no encontrada")

    // Decodificar credenciales Firebase BASE64
    val credentialsBytes = Base64.getDecoder().decode(base64)
    val googleCredentials = GoogleCredentials.fromStream(credentialsBytes.inputStream())

    println("Firebase URL: $firebaseUrl")
    println("Credenciales Firebase cargadas correctamente")

    // Inicializar Firebase
    FirebaseInit.initialize(googleCredentials, firebaseUrl)

    // Repositorios
    val preguntaRepo = PreguntaRepository()
    val quizRepo = QuizRepository()
    val cursoRepo = CursoRepository()
    val solicitudPreguntasRepo = SolicitudPreguntasRepository()

    // Servicios
    val servicioSeleccion = ServicioSeleccionPreguntas(preguntaRepo, cursoRepo)
    val quizService = QuizService(
        quizRepo = quizRepo,
        preguntaRepo = preguntaRepo,
        cursoRepo = cursoRepo,
        servicioSeleccion = servicioSeleccion,
        solicitudPreguntasRepo = solicitudPreguntasRepo
    )

    val servicioReportes = ServicioReportesExcel(quizRepo, cursoRepo)

    install(ContentNegotiation) { json() }

    // JWT dummy
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                JWT.require(Algorithm.HMAC256("dummy-secret")).build()
            )
            validate { JWTPrincipal(it.payload) }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token inválido"))
            }
        }
    }

    routing {
        get("/") { call.respondText("Servidor EduRacha corriendo correctamente") }

        authRoutes()
        usuarioRoutes()
        cursoRoutes()
        solicitudCursoRoutes()
        chatRoutes()
        preguntasRoutes()
        quizRoutes(quizService, quizRepo, cursoRepo)
        reportesRoutes(servicioReportes)
    }

    configureCronJobs(servicioReportes, cursoRepo, quizRepo)
}

fun Application.configureCronJobs(
    servicioReportes: ServicioReportesExcel,
    cursoRepo: CursoRepository,
    quizRepo: QuizRepository
) {
    val cronJob = CronJobReportes(servicioReportes, cursoRepo)
    cronJob.iniciar()

    environment.monitor.subscribe(ApplicationStopped) {
        cronJob.detener()
    }

    routing {
        post("/admin/generar-reportes") {
            val user = call.getUserFromToken()
                ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Token inválido o expirado")
                )

            if (user.rol !in listOf("admin", "docente")) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "Acceso denegado")
                )
            }

            GlobalScope.launch { cronJob.ejecutarManualmente() }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Generación de reportes iniciada"))
        }
    }
}
