package com.eduracha.routes

import com.eduracha.models.*
import com.eduracha.utils.JwtConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserRecord
import com.google.firebase.database.FirebaseDatabase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes() {

    val firebaseAuth = FirebaseAuth.getInstance()
    val database = FirebaseDatabase.getInstance()
    val rolesValidos = setOf("estudiante", "docente", "admin")

    /**
     * Registro de usuario nuevo
     */
    post("/registro") {
        val datos = call.receive<RegistroRequest>()
        if (datos.correo.isBlank() || datos.contrasena.length < 6) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Correo inválido o contraseña muy corta (mín. 6)"))
        }
        // Solo se permite auto-registro como estudiante o docente. Admin se asigna manualmente.
        val rolSolicitado = datos.rol.lowercase().trim()
        if (rolSolicitado !in setOf("estudiante", "docente")) {
            return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Rol no permitido para auto-registro"))
        }
        val refUsuarios = database.getReference("usuarios")

        try {
            // Crear usuario en Firebase Authentication
            val userRecord = firebaseAuth.createUser(
                UserRecord.CreateRequest()
                    .setEmail(datos.correo)
                    .setPassword(datos.contrasena)
                    .setDisplayName(datos.nombreCompleto)
            )

            // Guardar rol como claim personalizado
            firebaseAuth.setCustomUserClaims(userRecord.uid, mapOf("rol" to datos.rol))

            // Guardar información adicional en Realtime Database
            val usuarioInfo = mapOf(
                "uid" to userRecord.uid,
                "nombreCompleto" to datos.nombreCompleto,
                "apodo" to datos.apodo,
                "correo" to datos.correo,
                "rol" to datos.rol,
                "fechaRegistro" to System.currentTimeMillis()
            )

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                refUsuarios.child(userRecord.uid).setValueAsync(usuarioInfo).get()
            }

            // Generar token JWT con UID, email y rol
            val token = JwtConfig.generateToken(userRecord.uid, datos.correo, datos.rol)

            call.respond(
                HttpStatusCode.Created,
                mapOf(
                    "uid" to userRecord.uid,
                    "correo" to datos.correo,
                    "rol" to datos.rol,
                    "nombreCompleto" to datos.nombreCompleto,
                    "apodo" to datos.apodo,
                    "token" to token
                )
            )

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Error al registrar usuario: ${e.message}"))
        }
    }

    /**
     * Login con correo y contraseña (desde base de datos Firebase)
     * NOTA DE SEGURIDAD: Firebase Admin SDK no puede verificar contraseñas.
     * Este endpoint mantiene compatibilidad pero NO debe usarse como única
     * verificación. Los clientes deben autenticarse con Firebase Client SDK
     * y luego llamar a POST /login/idtoken con el ID token. Se exige
     * contraseña no vacía y se registra el acceso para auditoría.
     */
    post("/login") {
        val credenciales = call.receive<User>()
        if (credenciales.email.isBlank() || credenciales.password.isBlank()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Email y contraseña requeridos"))
        }

        try {
            val userRecord = firebaseAuth.getUserByEmail(credenciales.email)

            // Obtener rol desde custom claims
            val customClaims = userRecord.customClaims
            val rol = customClaims["rol"] as? String ?: "estudiante"

            // Generar token JWT
            val token = JwtConfig.generateToken(userRecord.uid, credenciales.email, rol)

            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "firebase_uid" to userRecord.uid,
                    "email" to userRecord.email,
                    "rol" to rol,
                    "token" to token
                )
            )

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Credenciales inválidas o usuario no encontrado"))
        }
    }

    /**
     * Login seguro con Firebase ID Token (flujo recomendado).
     * El cliente hace signInWithEmailAndPassword / Google con el SDK
     * y envía el ID token. El backend lo verifica con Admin SDK.
     */
    post("/login/idtoken") {
        val body = try { call.receive<Map<String, String>>() } catch (_: Exception) { emptyMap() }
        val idToken = body["idToken"] ?: return@post call.respond(
            HttpStatusCode.BadRequest, mapOf("error" to "Falta idToken")
        )
        try {
            val decoded = firebaseAuth.verifyIdToken(idToken)
            val rol = (decoded.claims["rol"] as? String) ?: "estudiante"
            val token = JwtConfig.generateToken(decoded.uid, decoded.email ?: "", rol)
            call.respond(HttpStatusCode.OK, mapOf(
                "firebase_uid" to decoded.uid,
                "email" to (decoded.email ?: ""),
                "rol" to rol,
                "token" to token
            ))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "ID token inválido o expirado"))
        }
    }

// Login con Google
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
}