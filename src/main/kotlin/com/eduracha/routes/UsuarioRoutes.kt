package com.eduracha.routes

import com.eduracha.models.ActualizarUsuarioRequest
import com.eduracha.utils.JwtConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserRecord
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.usuarioRoutes() {
    val firebaseAuth = FirebaseAuth.getInstance()

    // Helper: extraer email del JWT (Authorization: Bearer <token>)
    suspend fun obtenerEmailDesdeJWT(call: ApplicationCall): String? {
        val authHeader = call.request.headers["Authorization"] ?: return null
        if (!authHeader.startsWith("Bearer ")) return null
        val token = authHeader.removePrefix("Bearer ").trim()
        val decoded = JwtConfig.verifyToken(token) ?: return null
        return decoded.getClaim("email").asString()
    }

    // Helper: convertir UserRecord a mapa simple para respuesta
    fun userRecordToMap(user: UserRecord): Map<String, Any?> {
        return mapOf(
            "uid" to user.uid,
            "correo" to user.email,
            "nombreCompleto" to user.displayName,
            "emailVerified" to user.isEmailVerified,
            "customClaims" to user.customClaims
        )
    }

    // Obtener información del usuario autenticado (token JWT)
    get("/usuario/me") {
        val email = obtenerEmailDesdeJWT(call)
        if (email == null) {
            return@get call.respond(HttpStatusCode.Unauthorized, "Token faltante o inválido")
        }

        try {
            val user = firebaseAuth.getUserByEmail(email)
            call.respond(HttpStatusCode.OK, userRecordToMap(user))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.NotFound, "Usuario no encontrado en Firebase: ${e.message}")
        }
    }

    // Actualizar datos del usuario autenticado
    put("/usuario/me") {
        val email = obtenerEmailDesdeJWT(call)
        if (email == null) {
            return@put call.respond(HttpStatusCode.Unauthorized, "Token faltante o inválido")
        }

        val body = try {
            call.receive<ActualizarUsuarioRequest>()
        } catch (e: Exception) {
            return@put call.respond(HttpStatusCode.BadRequest, "Cuerpo inválido: ${e.message}")
        }

        try {
            val usuarioActual = firebaseAuth.getUserByEmail(email)
            val uid = usuarioActual.uid

            // Construir UpdateRequest y aplicar solo campos no-nulos
            val updateRequest = UserRecord.UpdateRequest(uid)
            var hasUpdate = false
            body.nombreCompleto?.let { updateRequest.setDisplayName(it); hasUpdate = true }
            body.correo?.let { updateRequest.setEmail(it); hasUpdate = true }
            body.contrasena?.let { updateRequest.setPassword(it); hasUpdate = true }

            val updatedUser = if (hasUpdate) firebaseAuth.updateUser(updateRequest) else usuarioActual

            // Manejar claims (apodo / rol) conservando los claims existentes
            val currentClaims = (updatedUser.customClaims ?: emptyMap<String, Any>()).toMutableMap()
            body.apodo?.let { currentClaims["apodo"] = it }
            body.rol?.let { currentClaims["rol"] = it }

            if (body.apodo != null || body.rol != null) {
                firebaseAuth.setCustomUserClaims(uid, currentClaims)
            }

            val userFinal = firebaseAuth.getUser(uid) // obtener versión final
            call.respond(HttpStatusCode.OK, userRecordToMap(userFinal))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.BadRequest, "Error al actualizar: ${e.message}")
        }
    }

    // Obtener info de un usuario por UID (solo admin/docente — verificamos claim 'rol')
    get("/usuario/{uid}") {
        val emailSolicitante = obtenerEmailDesdeJWT(call)
        if (emailSolicitante == null) {
            return@get call.respond(HttpStatusCode.Unauthorized, "Token faltante o inválido")
        }

        val uidSolicitado = call.parameters["uid"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            "Falta parámetro uid"
        )

        try {
            val solicitante = firebaseAuth.getUserByEmail(emailSolicitante)
            val rolSolicitante = solicitante.customClaims?.get("rol") as? String

            // Permitir si es el mismo usuario o si su rol es 'docente' o 'admin'
            if (solicitante.uid != uidSolicitado && rolSolicitante != "docente" && rolSolicitante != "admin") {
                return@get call.respond(HttpStatusCode.Forbidden, "No autorizado para ver otros usuarios")
            }

            val usuario = firebaseAuth.getUser(uidSolicitado)
            call.respond(HttpStatusCode.OK, userRecordToMap(usuario))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.NotFound, "Usuario no encontrado: ${e.message}")
        }
    }

    // Listar todos los usuarios (solo 'docente' o 'admin')
    get("/usuarios") {
        val emailSolicitante = obtenerEmailDesdeJWT(call)
        if (emailSolicitante == null) {
            return@get call.respond(HttpStatusCode.Unauthorized, "Token faltante o inválido")
        }

        try {
            val solicitante = firebaseAuth.getUserByEmail(emailSolicitante)
            val rolSolicitante = solicitante.customClaims?.get("rol") as? String

            if (rolSolicitante != "docente" && rolSolicitante != "admin") {
                return@get call.respond(HttpStatusCode.Forbidden, "Acceso denegado: se requiere rol docente o admin")
            }

            val list = firebaseAuth.listUsers(null).iterateAll().map { userRecordToMap(it) }
            call.respond(HttpStatusCode.OK, mapOf("usuarios" to list))
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, "Error al listar usuarios: ${e.message}")
        }
    }

    // Eliminar un usuario por UID (solo 'docente' o 'admin')
    delete("/usuario/{uid}") {
        val emailSolicitante = obtenerEmailDesdeJWT(call)
        if (emailSolicitante == null) {
            return@delete call.respond(HttpStatusCode.Unauthorized, "Token faltante o inválido")
        }

        val uidAEliminar = call.parameters["uid"] ?: return@delete call.respond(
            HttpStatusCode.BadRequest,
            "Falta parámetro uid"
        )

        try {
            val solicitante = firebaseAuth.getUserByEmail(emailSolicitante)
            val rolSolicitante = solicitante.customClaims?.get("rol") as? String

            if (rolSolicitante != "docente" && rolSolicitante != "admin") {
                return@delete call.respond(HttpStatusCode.Forbidden, "Acceso denegado: se requiere rol docente o admin")
            }

            firebaseAuth.deleteUser(uidAEliminar)
            call.respond(HttpStatusCode.OK, "Usuario eliminado: $uidAEliminar")
        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(HttpStatusCode.BadRequest, "Error al eliminar usuario: ${e.message}")
        }
    }
}
