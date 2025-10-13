package com.eduracha.routes

import com.eduracha.models.*
import com.eduracha.utils.JwtConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserRecord
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes() {

    val firebaseAuth = FirebaseAuth.getInstance()

    // Registro de usuario
    post("/registro") {
    val datos = call.receive<RegistroRequest>()
    val database = com.google.firebase.database.FirebaseDatabase.getInstance()
    val refUsuarios = database.getReference("usuarios")

    try {
        //  Crear usuario en Firebase Authentication
        val userRecord = firebaseAuth.createUser(
            UserRecord.CreateRequest()
                .setEmail(datos.correo)
                .setPassword(datos.contrasena)
                .setDisplayName(datos.nombreCompleto)
        )

        //  Guardar rol como claim personalizado
        firebaseAuth.setCustomUserClaims(userRecord.uid, mapOf("rol" to datos.rol))

        //  Guardar información adicional en Realtime Database
        val usuarioInfo = mapOf(
            "uid" to userRecord.uid,
            "nombreCompleto" to datos.nombreCompleto,
            "apodo" to datos.apodo,
            "correo" to datos.correo,
            "rol" to datos.rol,
            "fechaRegistro" to System.currentTimeMillis()
        )

        refUsuarios.child(userRecord.uid).setValueAsync(usuarioInfo)

        // Responder al cliente
        call.respond(
            HttpStatusCode.Created,
            mapOf(
                "uid" to userRecord.uid,
                "correo" to datos.correo,
                "rol" to datos.rol,
                "nombreCompleto" to datos.nombreCompleto,
                "apodo" to datos.apodo
            )
        )
    } catch (e: Exception) {
        e.printStackTrace()
        call.respond(HttpStatusCode.BadRequest, "Error al registrar usuario: ${e.message}")
    }
}

    //  Login con correo y contraseña
    post("/login") {
        val credenciales = call.receive<User>()

        try {
            val userRecord = firebaseAuth.getUserByEmail(credenciales.email)

            // Nota: Firebase Admin SDK no valida contraseñas.
            // Se recomienda validar desde frontend o con Firebase Auth REST API.

            val token = JwtConfig.generateToken(credenciales.email)
            call.respond(mapOf(
                "firebase_uid" to userRecord.uid,
                "email" to userRecord.email,
                "token" to token
            ))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.Unauthorized, "Credenciales inválidas o usuario no encontrado")
        }
    }

    //  Login con Google
    post("/login/google") {
        val data = call.receive<GoogleLoginRequest>()
        try {
            val decodedToken = firebaseAuth.verifyIdToken(data.googleToken)
            val email = decodedToken.email ?: "usuario_google@eduracha.com"

            val jwtToken = JwtConfig.generateToken(email)
            call.respond(mapOf(
                "firebase_uid" to decodedToken.uid,
                "email" to email,
                "token" to jwtToken
            ))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.Unauthorized, "Token de Google inválido o expirado")
        }
    }


    /* // Simulación de login con Google (para pruebas sin Firebase Auth en frontend)
    post("/login/google/mock") {
    val data = call.receive<Map<String, String>>()
    val email = data["email"] ?: return@post call.respondText(
        "El campo 'email' es obligatorio",
        status = HttpStatusCode.BadRequest
    )

    try {
        val firebaseAuth = FirebaseAuth.getInstance()

        // Verificar si el usuario ya existe
        val userRecord = try {
            firebaseAuth.getUserByEmail(email)
        } catch (e: Exception) {
            // Si no existe, lo creamos
            val newUser = UserRecord.CreateRequest()
                .setEmail(email)
                .setPassword("google_temp_pass_${System.currentTimeMillis()}") // contraseña temporal
                .setDisplayName("Usuario Google Simulado")
            firebaseAuth.createUser(newUser)
        }

        // Creamos un token simulado JWT de tu backend
        val mockToken = "jwt_simulado_${userRecord.uid}"

        call.respond(
            mapOf(
                "mensaje" to "Inicio de sesión simulado exitoso y usuario registrado/verificado en Firebase",
                "firebase_uid" to userRecord.uid,
                "email" to userRecord.email,
                "token" to mockToken
            )
        )
    } catch (e: Exception) {
        e.printStackTrace()
        call.respond(
            HttpStatusCode.InternalServerError,
            "Error al registrar/verificar usuario en Firebase: ${e.message}"
        )
    }
}

 */




}
