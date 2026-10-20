package com.eduracha.routes

import com.eduracha.utils.lecturaFirebase
import com.eduracha.utils.reanudarConError
import com.eduracha.utils.verifyFirebaseToken
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume

/**
 * Preferencias visuales del usuario (tema claro/oscuro y efectos de sonido).
 *
 * Seguridad:
 *  - Requiere token de Firebase (Authorization: Bearer <idToken>).
 *  - El UID se toma SIEMPRE del token: un usuario solo puede leer/escribir sus propias preferencias.
 *  - Lista blanca de valores; cualquier otro valor se rechaza con 400.
 *
 * Almacenamiento: usuarios/{uid}/preferencias = { tema, sonidos, actualizado }
 */
@Serializable
data class PreferenciasUsuario(
    val tema: String = "SISTEMA",
    val sonidos: Boolean = true
)

private val TEMAS_VALIDOS = setOf("SISTEMA", "CLARO", "OSCURO")

fun Route.preferenciasRoutes() {

    route("/api/usuarios/me/preferencias") {

        get {
            val usuario = call.verifyFirebaseToken()
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token inválido o ausente"))

            try {
                val ref = FirebaseDatabase.getInstance().getReference("usuarios/${usuario.uid}/preferencias")
                val prefs: PreferenciasUsuario? = lecturaFirebase { cont ->
                    ref.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            try {
                                if (!snapshot.exists()) {
                                    cont.resume(null)
                                    return
                                }
                                val tema = snapshot.child("tema").getValue(String::class.java)
                                    ?.uppercase()?.takeIf { it in TEMAS_VALIDOS } ?: "SISTEMA"
                                val sonidos = snapshot.child("sonidos").getValue(Boolean::class.java) ?: true
                                cont.resume(PreferenciasUsuario(tema, sonidos))
                            } catch (e: Exception) {
                                reanudarConError(cont, e, "PreferenciasRoutes.get")
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            cont.resume(null)
                        }
                    })
                }
                // 204 = el usuario aún no ha guardado preferencias (la app conserva su elección local)
                if (prefs == null) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.OK, prefs)
            } catch (e: Exception) {
                call.application.environment.log.warn("Error leyendo preferencias: ${e.message}")
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "No se pudieron leer las preferencias"))
            }
        }

        put {
            val usuario = call.verifyFirebaseToken()
                ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token inválido o ausente"))

            val body = try {
                call.receive<PreferenciasUsuario>()
            } catch (e: Exception) {
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Cuerpo inválido"))
            }

            val tema = body.tema.uppercase().trim()
            if (tema !in TEMAS_VALIDOS) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Tema no válido. Usa SISTEMA, CLARO u OSCURO")
                )
            }

            try {
                val ref = FirebaseDatabase.getInstance().getReference("usuarios/${usuario.uid}/preferencias")
                withContext(Dispatchers.IO) {
                    ref.setValueAsync(
                        mapOf(
                            "tema" to tema,
                            "sonidos" to body.sonidos,
                            "actualizado" to System.currentTimeMillis()
                        )
                    ).get()
                }
                call.respond(HttpStatusCode.OK, PreferenciasUsuario(tema, body.sonidos))
            } catch (e: Exception) {
                call.application.environment.log.warn("Error guardando preferencias: ${e.message}")
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "No se pudieron guardar las preferencias"))
            }
        }
    }
}
