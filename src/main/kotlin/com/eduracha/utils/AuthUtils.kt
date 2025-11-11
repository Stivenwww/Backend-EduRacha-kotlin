package com.eduracha.utils

import com.eduracha.models.Usuario
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class UserTokenData(
    val uid: String,
    val email: String,
    val rol: String? = "estudiante"
)

suspend fun ApplicationCall.verifyFirebaseToken(): UserTokenData? {
    val authHeader = request.headers["Authorization"] ?: return null
    if (!authHeader.startsWith("Bearer ")) return null

    val token = authHeader.removePrefix("Bearer ").trim()

    return try {
        val firebaseToken = FirebaseAuth.getInstance().verifyIdToken(token)

        UserTokenData(
            uid = firebaseToken.uid,
            email = firebaseToken.email ?: return null,
            rol = firebaseToken.claims["rol"]?.toString() ?: "estudiante"
        )
    } catch (e: Exception) {
        println("Error verificando token Firebase: ${e.message}")
        null
    }
}

suspend fun ApplicationCall.getUserFromToken(): Usuario? {
    val tokenData = verifyFirebaseToken() ?: return null

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
        println("Excepción en getUserFromToken: ${e.message}")
        null
    }
}

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
        println("Excepción en getUserByUid: ${e.message}")
        null
    }
}
