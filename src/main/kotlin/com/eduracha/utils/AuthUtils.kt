package com.eduracha.utils

import com.auth0.jwt.interfaces.DecodedJWT
import com.eduracha.models.Usuario
import com.google.firebase.database.*
import io.ktor.server.application.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Datos básicos que vienen del token
data class UserTokenData(
    val email: String,
    val rol: String? = null
)

// Verifica y decodifica el token JWT
fun ApplicationCall.getUserTokenData(): UserTokenData? {
    val authHeader = request.headers["Authorization"] ?: return null
    if (!authHeader.startsWith("Bearer ")) return null

    val token = authHeader.removePrefix("Bearer ").trim()
    val decoded = JwtConfig.verifyToken(token) ?: return null

    val email = decoded.getClaim("email").asString() ?: return null
    val rol = decoded.getClaim("rol")?.asString()

    return UserTokenData(email, rol)
}

// Obtiene el usuario completo desde Firebase usando el correo del token
suspend fun ApplicationCall.getUserFromToken(): Usuario? {
    val tokenData = getUserTokenData() ?: return null
    return try {
        val database = FirebaseDatabase.getInstance()
        suspendCancellableCoroutine { cont ->
            database.getReference("usuarios")
                .orderByChild("correo")
                .equalTo(tokenData.email)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val usuarioSnapshot = snapshot.children.firstOrNull()
                        val usuario = usuarioSnapshot?.getValue(Usuario::class.java)
                        cont.resume(usuario)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        println("Error al obtener usuario: ${error.message}")
                        cont.resume(null)
                    }
                })
        }
    } catch (e: Exception) {
        println("Error en getUserFromToken: ${e.message}")
        null
    }
}

// Obtiene el usuario directamente si ya conoces su UID
suspend fun ApplicationCall.getUserByUid(uid: String): Usuario? {
    return try {
        val database = FirebaseDatabase.getInstance()
        suspendCancellableCoroutine { cont ->
            database.getReference("usuarios/$uid")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        cont.resume(snapshot.getValue(Usuario::class.java))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        println("Error al obtener usuario por UID: ${error.message}")
                        cont.resume(null)
                    }
                })
        }
    } catch (e: Exception) {
        println("Error en getUserByUid: ${e.message}")
        null
    }
}
