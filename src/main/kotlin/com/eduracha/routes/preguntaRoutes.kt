package com.eduracha.routes

import com.eduracha.utils.exigirRol
import com.eduracha.models.Pregunta
import com.eduracha.repository.PreguntaRepository
import com.eduracha.repository.ExplicacionRepository
import com.eduracha.services.OpenAIService
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.toByteArray
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.github.cdimascio.dotenv.dotenv
import kotlinx.serialization.Serializable

@Serializable
data class PreguntasIAResponse(
    val message: String,
    val total: Int,
    val preguntas: List<Pregunta>
)

fun Application.preguntasRoutes(aiService: com.eduracha.ai.AiService? = null) {
    val repo = PreguntaRepository()
    val explicacionRepo = ExplicacionRepository()

    routing {

        // RUTAS CRUD DE PREGUNTAS
        route("/api/preguntas") {

            // GET /api/preguntas?cursoId=...&estado=...
            // Contrato: `estado` filtra siempre (con o sin cursoId).
            // `estado=todos` o ausente = sin filtro.
            get {
                call.exigirRol("docente", "admin") ?: return@get
                val cursoId = call.request.queryParameters["cursoId"]
                val estado = call.request.queryParameters["estado"]?.trim()?.takeIf { it.isNotEmpty() }

                try {
                    val preguntas = if (!cursoId.isNullOrEmpty()) {
                        repo.obtenerPreguntasPorCursoYEstado(cursoId, estado)
                    } else if (estado != null && !estado.equals("todos", ignoreCase = true)) {
                        com.eduracha.repository.filtrarPreguntasPorEstado(repo.obtenerPreguntas(), estado)
                    } else {
                        repo.obtenerPreguntas()
                    }

                    call.respond(HttpStatusCode.OK, preguntas)
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Error interno del servidor"))
                    )
                }
            }

            // GET /api/preguntas/{id}
            get("/{id}") {
                call.exigirRol("docente", "admin") ?: return@get
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                try {
                    val pregunta = repo.obtenerPreguntaPorId(id)
                    if (pregunta != null) call.respond(HttpStatusCode.OK, pregunta)
                    else call.respond(HttpStatusCode.NotFound, mapOf("error" to "Pregunta no encontrada"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // POST /api/preguntas
            post {
                call.exigirRol("docente", "admin") ?: return@post
                try {
                    val pregunta = call.receive<Pregunta>()
                    
                    // VALIDACIÓN: Verificar que existe explicación aprobada para el tema
                    if (!pregunta.temaId.isNullOrBlank() && !pregunta.cursoId.isNullOrBlank()) {
                        val tieneExplicacion = explicacionRepo.tieneExplicacionAprobada(
                            pregunta.cursoId, 
                            pregunta.temaId
                        )
                        
                        if (!tieneExplicacion) {
                            return@post call.respond(
                                HttpStatusCode.PreconditionFailed,
                                mapOf(
                                    "error" to "No se puede crear la pregunta. El tema no tiene una explicación aprobada.",
                                    "mensaje" to "Primero debe generar y aprobar una explicación para este tema."
                                )
                            )
                        }
                    }
                    
                    val id = repo.crearPregunta(pregunta)
                    call.respond(
                        HttpStatusCode.Created,
                        mapOf("message" to "Pregunta creada correctamente", "id" to id)
                    )
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (e.message ?: "Error al crear la pregunta"))
                    )
                }
            }

            // PUT /api/preguntas/{id}
            put("/{id}") {
                call.exigirRol("docente", "admin") ?: return@put
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                try {
                    val pregunta = call.receive<Pregunta>()
                    repo.actualizarPregunta(id, pregunta)
                    call.respond(mapOf("message" to "Pregunta actualizada correctamente"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // DELETE /api/preguntas/{id}
            delete("/{id}") {
                call.exigirRol("docente", "admin") ?: return@delete
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                try {
                    repo.eliminarPregunta(id)
                    call.respond(mapOf("message" to "Pregunta eliminada correctamente"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // PUT /api/preguntas/{id}/estado
            put("/{id}/estado") {
                call.exigirRol("docente", "admin") ?: return@put
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                try {
                    val data = call.receive<Map<String, String>>()
                    val estado = data["estado"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val notas = data["notas"]
                    repo.actualizarEstadoPregunta(id, estado, notas)
                    call.respond(mapOf("message" to "Estado actualizado", "nuevoEstado" to estado))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
                }
            }

            // DELETE /api/preguntas/cache
            delete("/cache") {
                call.exigirRol("docente", "admin") ?: return@delete
                repo.limpiarCacheFirebase()
                call.respond(mapOf("message" to "Caché de Firebase limpiada"))
            }
        }

        // LECTURA DE PDF: devuelve el texto real extraído (o un error claro si no se puede leer)
        post("/api/pdf/extraer") {
            call.exigirRol("docente", "admin") ?: return@post
            val pdf = recibirPdf(call) ?: return@post
            val resultado = com.eduracha.utils.LectorPdf.leer(pdf.second, pdf.first)
            if (resultado.error != null) {
                return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to resultado.error))
            }
            call.respond(HttpStatusCode.OK, resultado)
        }

        // RUTAS DE IA (Generación Automática) — usa AiService desacoplado si existe
        route("/api/preguntas/ia") {

            // POST /api/preguntas/ia/generar-desde-pdf (multipart: archivo, cursoId, temaId, cantidad, dificultad)
            // Lee el PDF, genera preguntas con su contenido real y las deja pendientes de revisión.
            post("/generar-desde-pdf") {
                call.exigirRol("docente", "admin") ?: return@post
                val ia = aiService ?: return@post call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf("error" to "La IA no está configurada en el servidor (AI_PROVIDER / AI_API_KEY).")
                )
                try {
                    val campos = mutableMapOf<String, String>()
                    var nombre = "documento.pdf"
                    var bytes: ByteArray? = null
                    call.receiveMultipart().forEachPart { parte ->
                        when (parte) {
                            is io.ktor.http.content.PartData.FormItem -> campos[parte.name ?: ""] = parte.value
                            is io.ktor.http.content.PartData.FileItem -> {
                                nombre = parte.originalFileName ?: nombre
                                bytes = parte.provider().toByteArray()
                            }
                            else -> {}
                        }
                        parte.dispose()
                    }
                    val cursoId = campos["cursoId"]?.takeIf { it.isNotBlank() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el curso"))
                    val temaId = campos["temaId"]?.takeIf { it.isNotBlank() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el tema"))
                    val cantidad = (campos["cantidad"]?.toIntOrNull() ?: 5).coerceIn(1, 20)
                    val dificultad = campos["dificultad"]?.lowercase()?.takeIf { it in setOf("facil", "medio", "dificil") }
                    val contenido = bytes ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Adjunta un archivo PDF"))

                    if (!explicacionRepo.tieneExplicacionAprobada(cursoId, temaId)) {
                        return@post call.respond(
                            HttpStatusCode.PreconditionFailed,
                            mapOf("error" to "No se pueden generar preguntas. El tema no tiene una explicación aprobada.")
                        )
                    }

                    val pdf = com.eduracha.utils.LectorPdf.leer(contenido, nombre)
                    if (pdf.error != null) {
                        return@post call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to pdf.error))
                    }
                    val validadas = ia.generarPreguntasDesdeDocumento(pdf.texto, cantidad, dificultad)
                    val guardadas = ia.guardarPreguntasGeneradas(cursoId, temaId, validadas, "PDF: ${pdf.nombre} (${pdf.paginas} págs.)")
                    val aviso = if (pdf.truncado) " Solo se usó la primera parte del documento por su extensión." else ""
                    call.respond(
                        HttpStatusCode.OK,
                        PreguntasIAResponse(
                            message = "Se leyeron ${pdf.paginas} páginas de \"${pdf.nombre}\" y se generaron ${guardadas.size} preguntas. " +
                                "Quedan pendientes de revisión.$aviso",
                            total = guardadas.size,
                            preguntas = guardadas
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Error generando preguntas desde el PDF"))
                    )
                }
            }

            // POST /api/preguntas/ia/generar
            post("/generar") {
                call.exigirRol("docente", "admin") ?: return@post
                try {
                    val data = call.receive<Map<String, String>>()
                    val cursoId = data["cursoId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Falta cursoId")
                    val temaId = data["temaId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Falta temaId")
                    val temaTexto = data["temaTexto"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Falta temaTexto")
                    val cantidad = (data["cantidad"]?.toIntOrNull() ?: 5).coerceIn(1, 20)
                    if (temaTexto.length > 4000) {
                        return@post call.respond(HttpStatusCode.BadRequest, "temaTexto demasiado largo (máx. 4000)")
                    }

                    // VALIDACIÓN CRÍTICA: Verificar que existe explicación aprobada
                    val tieneExplicacion = explicacionRepo.tieneExplicacionAprobada(cursoId, temaId)

                    if (!tieneExplicacion) {
                        return@post call.respond(
                            HttpStatusCode.PreconditionFailed,
                            mapOf(
                                "error" to "No se pueden generar preguntas. El tema no tiene una explicación aprobada.",
                                "mensaje" to "FLUJO REQUERIDO: 1) Generar explicación, 2) Validar explicación, 3) Generar preguntas",
                                "accionRequerida" to "Generar y aprobar explicación del tema"
                            )
                        )
                    }

                    val preguntas = if (aiService != null) {
                        aiService.generarYGuardarPreguntas(cursoId, temaId, temaTexto, cantidad)
                    } else {
                        // Fallback legacy OpenAI directo (compatibilidad)
                        val openAiKey = System.getenv("OPENAI_API_KEY")
                            ?: dotenv()["OPENAI_API_KEY"]
                            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "IA no configurada (AI_API_KEY/AI_PROVIDER)"))
                        val client = HttpClient(CIO)
                        try {
                            OpenAIService(client, openAiKey).generarYGuardarPreguntas(cursoId, temaId, temaTexto, cantidad)
                        } finally {
                            client.close()
                        }
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        PreguntasIAResponse(
                            message = "Preguntas generadas correctamente",
                            total = preguntas.size,
                            preguntas = preguntas
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "Error generando preguntas con IA"))
                    )
                }
            }
        }
    }
}

/** Lee el primer archivo de un formulario multipart. Responde 400 y devuelve null si no hay archivo. */
private suspend fun recibirPdf(call: ApplicationCall): Pair<String, ByteArray>? {
    var nombre = "documento.pdf"
    var bytes: ByteArray? = null
    call.receiveMultipart().forEachPart { parte ->
        if (parte is io.ktor.http.content.PartData.FileItem && bytes == null) {
            nombre = parte.originalFileName ?: nombre
            bytes = parte.provider().toByteArray()
        }
        parte.dispose()
    }
    val contenido = bytes
    if (contenido == null) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Adjunta un archivo PDF"))
        return null
    }
    return nombre to contenido
}
