package com.eduracha

import com.eduracha.routes.authRoutes
import com.eduracha.routes.usuarioRoutes
import com.eduracha.routes.cursoRoutes
import com.eduracha.routes.solicitudCursoRoutes
import com.eduracha.routes.chatRoutes
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
import com.eduracha.repository.PreguntaRepository  
import com.eduracha.routes.preguntasRoutes
import com.eduracha.repository.QuizRepository
import com.eduracha.services.QuizService
import com.eduracha.routes.quizRoutes




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

    val database = FirebaseDatabase.getInstance(firebaseUrl)

   
// Pasamos 'database' al repositorio (Realtime Database)

val preguntaRepo = PreguntaRepository()
val quizRepo = QuizRepository()
val quizService = QuizService(quizRepo, preguntaRepo)

    install(ContentNegotiation) {
        json()
    }
    routing {
        get("/") {
            call.respondText("Servidor EduRacha corriendo correctamente y conectado a Firestore")
        }

        // Rutas principales del proyecto
        authRoutes()
        usuarioRoutes()
        cursoRoutes()
        solicitudCursoRoutes()
        chatRoutes()
        preguntasRoutes()
        quizRoutes()

    }
}
