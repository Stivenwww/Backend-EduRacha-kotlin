package com.eduracha.utils

import com.eduracha.utils.lecturaFirebase
import com.eduracha.utils.reanudarConError

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

        val rolClaim = firebaseToken.claims["rol"]?.toString()?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        UserTokenData(
            uid = firebaseToken.uid,
            email = firebaseToken.email ?: return null,
            // El rol real vive en usuarios/{uid}/rol. Muchos usuarios (registro desde la app,
            // Google) no tienen el claim en el token: antes quedaban todos como "estudiante"
            // y los docentes recibían "Acceso denegado".
            rol = rolClaim ?: RolesUsuario.obtener(firebaseToken.uid) ?: "estudiante"
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
        lecturaFirebase { cont ->
            database.getReference("usuarios")
                .orderByChild("correo")
                .equalTo(tokenData.email)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        val usuarioSnapshot = snapshot.children.firstOrNull()
                        val usuario = usuarioSnapshot?.getValue(Usuario::class.java)
                        cont.resume(usuario)
                } catch (e: Exception) {
                    reanudarConError(cont, e, "AuthUtils.kt")
                }
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
        lecturaFirebase { cont ->
            database.getReference("usuarios/$uid")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        cont.resume(snapshot.getValue(Usuario::class.java))
                } catch (e: Exception) {
                    reanudarConError(cont, e, "AuthUtils.kt")
                }
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


/**
 * Rol del usuario leído de Realtime Database (usuarios/{uid}/rol), con caché corta
 * para no consultar Firebase en cada petición.
 */
object RolesUsuario {
    private const val VIGENCIA_MS = 60_000L
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    suspend fun obtener(uid: String): String? {
        val ahora = System.currentTimeMillis()
        cache[uid]?.let { (rol, hora) -> if (ahora - hora < VIGENCIA_MS) return rol }
        val rol = try {
            lecturaFirebase<String?> { cont ->
                FirebaseDatabase.getInstance().getReference("usuarios/$uid/rol")
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            cont.resume(snapshot.getValue(String::class.java)?.trim()?.lowercase())
                        }
                        override fun onCancelled(error: DatabaseError) {
                            cont.resume(null)
                        }
                    })
            }
        } catch (e: Exception) {
            println("No se pudo leer el rol de $uid: ${e.message}")
            null
        }
        if (rol != null) cache[uid] = rol to ahora
        return rol
    }

    fun olvidar(uid: String) { cache.remove(uid) }
}
