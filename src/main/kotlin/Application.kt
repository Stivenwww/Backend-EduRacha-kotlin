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
import com.eduracha.models.EstadoTema
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.io.File

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Intenta cargar .env si existe (desarrollo local)
    val dotenv = try {
        dotenv {
            ignoreIfMissing = true
        }
    } catch (e: Exception) {
        null
    }

    // Función helper: primero System.getenv, luego dotenv
    fun getEnv(key: String): String? {
        return System.getenv(key) ?: dotenv?.get(key)
    }

    val firebaseUrl = getEnv("FIREBASE_DATABASE_URL")
    val openAiKey = getEnv("OPENAI_API_KEY")
    val credentialsJson = getEnv("FIREBASE_CREDENTIALS_JSON")
    val credentialsPath = getEnv("GOOGLE_APPLICATION_CREDENTIALS")

    println("Variables de entorno cargadas correctamente")
    println("Firebase URL: $firebaseUrl")
    println("OpenAI Key (parcial): ${openAiKey?.take(8)}...")

    if (firebaseUrl.isNullOrEmpty()) {
        throw IllegalStateException("No se encontró FIREBASE_DATABASE_URL")
    }

    // Determinar qué método usar para las credenciales
    val finalCredentialsPath = when {
        // Si existe FIREBASE_CREDENTIALS_JSON (producción), crear archivo temporal
        !credentialsJson.isNullOrEmpty() -> {
            println("Usando FIREBASE_CREDENTIALS_JSON desde variable de entorno")
            val tempFile = File.createTempFile("firebase-credentials", ".json")
            tempFile.deleteOnExit()
            tempFile.writeText(credentialsJson)
            tempFile.absolutePath
        }
        // Si existe GOOGLE_APPLICATION_CREDENTIALS (desarrollo local)
        !credentialsPath.isNullOrEmpty() -> {
            println("Usando GOOGLE_APPLICATION_CREDENTIALS: $credentialsPath")
            credentialsPath
        }
        else -> {
            throw IllegalStateException(
                "No se encontró FIREBASE_CREDENTIALS_JSON ni GOOGLE_APPLICATION_CREDENTIALS"
            )
        }
    }

    FirebaseInit.initialize(finalCredentialsPath, firebaseUrl)

    // Repositorios
    val preguntaRepo = PreguntaRepository()
    val quizRepo = QuizRepository()
    val cursoRepo = CursoRepository()
    val solicitudPreguntasRepo = SolicitudPreguntasRepository()

    // Servicios
    val servicioSeleccion = ServicioSeleccionPreguntas(
        preguntaRepo = preguntaRepo,
        cursoRepo = cursoRepo
    )

    val quizService = QuizService(
        quizRepo = quizRepo,
        preguntaRepo = preguntaRepo,
        cursoRepo = cursoRepo,
        servicioSeleccion = servicioSeleccion,
        solicitudPreguntasRepo = solicitudPreguntasRepo
    )

    val servicioReportes = ServicioReportesExcel(quizRepo, cursoRepo)

    install(ContentNegotiation) {
        json()
    }

    // JWT dummy solo para pruebas
    install(Authentication) {
        jwt("auth-jwt") {
            verifier {
                JWT
                    .require(Algorithm.HMAC256("dummy-secret"))
                    .build()
            }
            validate { credential ->
                JWTPrincipal(credential.payload)
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token inválido"))
            }
        }
    }

    routing {
        get("/") {
            call.respondText("Servidor EduRacha corriendo correctamente")
        }

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

            if (user.rol != "admin" && user.rol != "docente") {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Acceso denegado"))
                return@post
            }

            GlobalScope.launch {
                cronJob.ejecutarManualmente()
            }

            call.respond(HttpStatusCode.OK, mapOf("message" to "Generación de reportes iniciada"))
        }
    }
}