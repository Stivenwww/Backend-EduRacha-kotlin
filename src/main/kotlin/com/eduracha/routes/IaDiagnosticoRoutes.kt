package com.eduracha.routes

import com.eduracha.ai.AiConfig
import com.eduracha.ai.AiService
import com.eduracha.utils.verifyFirebaseToken
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticoIA(
    val proveedor: String?,
    val modelo: String?,
    val llaveConfigurada: Boolean,
    val llaveTermina: String?,
    val workspaceConfigurado: Boolean,
    val servicioIniciado: Boolean,
    val errorAlIniciar: String?,
    val pruebaOk: Boolean,
    val respuestaPrueba: String?,
    val errorPrueba: String?,
    val milisegundos: Long?
)

/**
 * GET /api/ia/diagnostico
 * Verifica la configuración de IA y hace una llamada mínima al proveedor.
 * Acceso: solo desde la misma PC (localhost) o con token de docente/admin. Nunca muestra la llave completa.
 */
fun Route.iaDiagnosticoRoutes(aiConfig: AiConfig?, aiService: AiService?, errorAlIniciar: String?) {
    get("/api/ia/diagnostico") {
        val host = call.request.origin.remoteHost
        val esLocal = host == "127.0.0.1" || host == "localhost" || host == "0:0:0:0:0:0:0:1" || host == "::1"
        if (!esLocal) {
            val usuario = call.verifyFirebaseToken()
            if (usuario == null || usuario.rol !in setOf("docente", "admin")) {
                return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Solo disponible en local o para docentes"))
            }
        }
        var ok = false
        var respuesta: String? = null
        var error: String? = null
        var ms: Long? = null
        if (aiService != null) {
            val inicio = System.currentTimeMillis()
            try {
                respuesta = aiService.probarConexion()
                ok = true
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
            ms = System.currentTimeMillis() - inicio
        }
        call.respond(
            HttpStatusCode.OK,
            DiagnosticoIA(
                proveedor = aiConfig?.provider,
                modelo = aiConfig?.model,
                llaveConfigurada = !aiConfig?.apiKey.isNullOrBlank(),
                llaveTermina = aiConfig?.apiKey?.takeIf { it.length > 8 }?.let { "..." + it.takeLast(4) },
                workspaceConfigurado = !aiConfig?.workspaceId.isNullOrBlank(),
                servicioIniciado = aiService != null,
                errorAlIniciar = errorAlIniciar,
                pruebaOk = ok,
                respuestaPrueba = respuesta,
                errorPrueba = error,
                milisegundos = ms
            )
        )
    }
}
