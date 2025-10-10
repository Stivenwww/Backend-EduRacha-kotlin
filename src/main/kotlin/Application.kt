package com.eduracha

import com.eduracha.routes.authRoutes
import com.eduracha.routes.usuarioRoutes
import com.eduracha.routes.cursoRoutes
import com.eduracha.routes.solicitudCursoRoutes
import com.eduracha.utils.FirebaseInit
import com.google.firebase.database.FirebaseDatabase
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    // Inicializar Firebase solo una vez
    FirebaseInit.initialize()

    // Obtener instancia de Realtime Database (si necesitas usarla)
    val database = FirebaseDatabase.getInstance("https://eduracha-41314-default-rtdb.firebaseio.com/")

    // Configurar JSON
    install(ContentNegotiation) {
        json()
    }

    // Definir rutas
    routing {
        get("/") {
            call.respondText("Servidor EduRacha conectado correctamente al Realtime Database ")
        }

        // Rutas personalizadas
        authRoutes()
        usuarioRoutes()
        cursoRoutes()
        solicitudCursoRoutes() 
    }
}
