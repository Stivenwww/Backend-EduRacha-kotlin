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

            // POST /api/preguntas/aprobar-pendientes  { cursoId, temaId? }
            // Aprueba de una vez todas las preguntas pendientes del curso (o solo las de un tema).
            post("/aprobar-pendientes") {
                val docente = call.exigirRol("docente", "admin") ?: return@post
                try {
                    val datos = call.receive<Map<String, String>>()
                    val cursoId = datos["cursoId"]?.takeIf { it.isNotBlank() }
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el curso"))
                    val temaId = datos["temaId"]?.takeIf { it.isNotBlank() }
                    if (docente.rol != "admin") {
                        val curso = com.eduracha.repository.CursoRepository().obtenerCursoPorId(cursoId)
                            ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Curso no encontrado"))
                        if (curso.docenteId.isNotBlank() && curso.docenteId != docente.uid) {
                            return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Solo el docente del curso puede aprobar sus preguntas"))
                        }
                    }
                    val ids = repo.obtenerPreguntasPorCursoYEstado(cursoId, "pendiente_revision")
                        .filter { temaId == null || it.temaId == temaId }
                        .mapNotNull { it.id }
                    repo.aprobarVarias(ids, docente.email.ifBlank { docente.uid })
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "message" to if (ids.isEmpty()) "No había preguntas pendientes." else "Se aprobaron ${ids.size} preguntas.",
                            "aprobadas" to ids.size.toString()
                        )
                    )
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No se pudieron aprobar: ${e.message}"))
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

        // POST /api/ia/explicacion-borrador  { titulo, contenido?, textoDocumento?, curso? }
        // Escribe la explicación de un tema que aún no existe (formulario "Agregar tema" al crear el curso).
        // No guarda nada: el docente la revisa o la edita y se guarda con el curso, pendiente de aprobación.
        post("/api/ia/explicacion-borrador") {
            call.exigirRol("docente", "admin") ?: return@post
            val ia = aiService ?: return@post call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "La IA no está configurada en el servidor. Puedes escribir la explicación manualmente.")
            )
            try {
                val datos = call.receive<kotlinx.serialization.json.JsonObject>()
                fun campo(n: String) = (datos[n] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
                val titulo = campo("titulo") ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Escribe primero el título del tema."))
                val documento = campo("textoDocumento")
                val explicacion = if (documento != null) {
                    ia.generarExplicacionDesdeDocumento(titulo, documento.take(200_000), campo("curso"))
                } else {
                    ia.generarExplicacion(titulo, campo("contenido"), campo("curso"))
                }
                call.respond(HttpStatusCode.OK, mapOf("explicacion" to explicacion, "fuente" to "ia"))
            } catch (e: Exception) {
                e.printStackTrace()
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "La IA no pudo escribir la explicación (${e.message ?: "sin detalle"}). Puedes escribirla manualmente.")
                )
            }
        }

        // RUTAS DE IA (Generación Automática) — usa AiService desacoplado si existe
        route("/api/preguntas/ia") {

            // (El PDF ya no se usa para generar preguntas: se usa al crear el tema, para escribir su explicación.)

            // POST /api/preguntas/ia/generar  { cursoId, temaId, temaTexto, cantidad (1-30) }
            // La fuente es la explicación aprobada del tema; no se repiten preguntas que el tema ya tiene.
            post("/generar") {
                val docenteQuePide = call.exigirRol("docente", "admin") ?: return@post
                try {
                    // Se lee como JSON libre: la app envía "cantidad" como número
                    val data = call.receive<kotlinx.serialization.json.JsonObject>()
                    fun campo(n: String) = (data[n] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
                    val cursoId = campo("cursoId") ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el curso"))
                    val temaId = campo("temaId") ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta el tema"))
                    val temaTexto = campo("temaTexto").orEmpty()
                    val maximo = com.eduracha.ai.AiService.MAX_PREGUNTAS
                    val cantidad = (campo("cantidad")?.toIntOrNull() ?: 5).coerceIn(1, maximo)

                    // VALIDACIÓN CRÍTICA: Verificar que existe explicación aprobada
                    val tema = explicacionRepo.obtenerTema(cursoId, temaId)
                    val explicacion = tema?.explicacion?.takeIf { tema.explicacionEstado == "aprobada" && it.isNotBlank() }
                    if (explicacion == null) {
                        return@post call.respond(
                            HttpStatusCode.PreconditionFailed,
                            mapOf(
                                "error" to "No se pueden generar preguntas. El tema no tiene una explicación aprobada.",
                                "mensaje" to "FLUJO REQUERIDO: 1) Generar explicación, 2) Validar explicación, 3) Generar preguntas",
                                "accionRequerida" to "Generar y aprobar explicación del tema"
                            )
                        )
                    }
                    val fuente = "Tema: ${tema.titulo}\n\nExplicación aprobada del tema:\n$explicacion" +
                        if (temaTexto.isNotBlank()) "\n\nNotas del docente:\n${temaTexto.take(1500)}" else ""
                    val existentes = runCatching {
                        repo.obtenerPreguntasPorCursoYEstado(cursoId, null).filter { it.temaId == temaId }.map { it.texto }
                    }.getOrDefault(emptyList())

                    val preguntas = if (aiService != null) {
                        aiService.generarYGuardarPreguntas(cursoId, temaId, fuente, cantidad, existentes)
                    } else {
                        // Fallback legacy OpenAI directo (compatibilidad, máximo 10)
                        val openAiKey = System.getenv("OPENAI_API_KEY")
                            ?: dotenv()["OPENAI_API_KEY"]
                            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "IA no configurada (AI_API_KEY/AI_PROVIDER)"))
                        val client = HttpClient(CIO)
                        try {
                            OpenAIService(client, openAiKey).generarYGuardarPreguntas(cursoId, temaId, fuente.take(4000), cantidad.coerceAtMost(10))
                        } finally {
                            client.close()
                        }
                    }

                    com.eduracha.services.Notificador.enviar(
                        docenteQuePide.uid,
                        "Preguntas listas para revisar",
                        "La IA generó ${preguntas.size} preguntas del tema ${tema.titulo}. Revísalas y apruébalas.",
                        "preguntas_pendientes",
                        mapOf("cursoId" to cursoId, "temaId" to temaId)
                    )
                    val mensaje = if (preguntas.size < cantidad)
                        "Se generaron ${preguntas.size} de $cantidad preguntas (la IA no encontró más preguntas distintas). Quedan pendientes de revisión."
                    else "Se generaron ${preguntas.size} preguntas. Quedan pendientes de revisión."
                    call.respond(
                        HttpStatusCode.OK,
                        PreguntasIAResponse(
                            message = mensaje,
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
