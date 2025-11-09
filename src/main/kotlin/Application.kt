package com.eduracha

import com.eduracha.routes.*
import com.eduracha.utils.FirebaseInit
import com.google.firebase.database.FirebaseDatabase
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

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val dotenv = dotenv {
        ignoreIfMissing = true
    }

    val firebaseUrl = dotenv["FIREBASE_DATABASE_URL"]
    val openAiKey = dotenv["OPENAI_API_KEY"]
    val credentialsPath = dotenv["GOOGLE_APPLICATION_CREDENTIALS"]

    println("Variables de entorno cargadas correctamente")
    println("Firebase URL: $firebaseUrl")
    println("OpenAI Key (parcial): ${openAiKey?.take(8)}...")

    if (firebaseUrl.isNullOrEmpty() || credentialsPath.isNullOrEmpty()) {
        throw IllegalStateException("No se encontraron las variables FIREBASE_DATABASE_URL o GOOGLE_APPLICATION_CREDENTIALS en el archivo .env")
    }

    FirebaseInit.initialize(credentialsPath, firebaseUrl)

    // Instanciar repositorios
    val preguntaRepo = PreguntaRepository()
    val quizRepo = QuizRepository()
    val cursoRepo = CursoRepository()
    
    // Instanciar servicios
    val servicioSeleccion = ServicioSeleccionPreguntas(preguntaRepo)
    val quizService = QuizService(quizRepo, preguntaRepo, cursoRepo, servicioSeleccion)
    val servicioReportes = ServicioReportesExcel(quizRepo, cursoRepo)

    install(ContentNegotiation) {
        json()
    }
    
    routing {
        get("/") {
            call.respondText("Servidor EduRacha corriendo correctamente")
        }

        // Rutas principales
        authRoutes()
        usuarioRoutes()
        cursoRoutes()
        solicitudCursoRoutes()
        chatRoutes()
        preguntasRoutes()
        quizRoutes(quizService)
        reportesRoutes(servicioReportes)
    }
    
    // Configurar cron jobs
    configureCronJobs(servicioReportes, cursoRepo)
}

fun Application.configureCronJobs(servicioReportes: ServicioReportesExcel, cursoRepo: CursoRepository) {
    val cronJob = CronJobReportes(servicioReportes, cursoRepo)
    
    cronJob.iniciar()
    
    environment.monitor.subscribe(ApplicationStopped) {
        cronJob.detener()
    }
    
    routing {
        post("/admin/generar-reportes") {
            val user = call.getUserFromToken()
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token inválido o expirado"))
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