package com.eduracha.utils

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

/**
 * Guardas de autorización reutilizables para las rutas.
 * Usan el token de Firebase que la app envía en cada petición (AuthInterceptor).
 *
 * Uso dentro de un handler:
 *   val usuario = call.exigirUsuario() ?: return@post
 *   val docente = call.exigirRol("docente", "admin") ?: return@put
 */

/** Devuelve el usuario autenticado o responde 401 y devuelve null. */
suspend fun ApplicationCall.exigirUsuario(): UserTokenData? {
    val usuario = verifyFirebaseToken()
    if (usuario == null) {
        respond(HttpStatusCode.Unauthorized, mapOf("error" to "Inicia sesión para continuar"))
    }
    return usuario
}

/** Devuelve el usuario si tiene alguno de los roles indicados; si no, responde 401/403 y devuelve null. */
suspend fun ApplicationCall.exigirRol(vararg roles: String): UserTokenData? {
    val usuario = exigirUsuario() ?: return null
    if (usuario.rol !in roles) {
        respond(HttpStatusCode.Forbidden, mapOf("error" to "No tienes permisos para esta acción"))
        return null
    }
    return usuario
}

/** El usuario es el dueño del recurso (mismo UID) o tiene un rol privilegiado. */
fun UserTokenData.esDuenoOPrivilegiado(uid: String, rolesPrivilegiados: Set<String> = setOf("docente", "admin")): Boolean =
    this.uid == uid || this.rol in rolesPrivilegiados
