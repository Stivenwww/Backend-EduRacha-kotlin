package com.eduracha.services

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import com.google.firebase.database.FirebaseDatabase
import java.time.Instant
import java.util.UUID

@Serializable
data class PreguntaAI(
    val texto: String,
    val opciones: List<OpcionAI>,
    val dificultad: String? = "medio",
    val metadatosIA: MetadatosIAAI? = null
)

@Serializable
data class OpcionAI(
    val id: Int,
    val texto: String,
    val esCorrecta: Boolean
)

@Serializable
data class MetadatosIAAI(
    val generadoPor: String? = "openai",
    val instruccion: String? = null
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.2,
    val max_tokens: Int = 1500
)

class OpenAIService(private val client: HttpClient, private val openAiApiKey: String) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generarYGuardarPreguntas(
        cursoId: String,
        temaId: String,
        temaTexto: String,
        cantidad: Int = 5
    ): List<com.eduracha.models.Pregunta> {

        val systemPrompt = """
            Eres un generador de preguntas educativas. 
            Devuelve **únicamente** un JSON válido con esta estructura exacta:
            {
              "preguntas": [
                {
                  "texto": "¿Qué es la fotosíntesis?",
                  "opciones": [
                    {"id": 1, "texto": "Proceso mediante el cual las plantas producen energía", "esCorrecta": true},
                    {"id": 2, "texto": "Un tipo de animal", "esCorrecta": false},
                    {"id": 3, "texto": "Una proteína", "esCorrecta": false}
                  ],
                  "dificultad": "medio",
                  "metadatosIA": {
                    "generadoPor": "openai",
                    "instruccion": "Generar preguntas sobre el tema X"
                  }
                }
              ]
            }
            No agregues explicaciones, texto adicional ni formato markdown.
        """.trimIndent()

        val userPrompt = """
            Genera $cantidad preguntas de opción múltiple sobre el tema "$temaTexto".
            Cada pregunta debe tener 3 opciones, un campo "dificultad" y "metadatosIA".
        """.trimIndent()

        val request = ChatRequest(
            model = "gpt-4o-mini",
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userPrompt)
            ),
            temperature = 0.2,
            max_tokens = 1200
        )

        val response = client.post("https://api.openai.com/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $openAiApiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }

        val respText = response.bodyAsText()

        try {
            val root = json.parseToJsonElement(respText).jsonObject
            val content = root["choices"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                ?: throw IllegalStateException("No se encontró contenido en la respuesta de OpenAI")

            val cleanContent = content
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val parsed = json.parseToJsonElement(cleanContent).jsonObject
            val preguntasArray = parsed["preguntas"]?.jsonArray
                ?: throw IllegalStateException("No se encontró el campo 'preguntas'")

            val aiPreguntas = preguntasArray.map {
                json.decodeFromJsonElement<PreguntaAI>(it)
            }

            // Guardar en Firebase directamente en el nodo "preguntas"
            val refBase = FirebaseDatabase.getInstance()
                .getReference("preguntas")

            val saved = mutableListOf<com.eduracha.models.Pregunta>()
            for (ai in aiPreguntas) {
                val newRef = refBase.push()
                val id = newRef.key ?: UUID.randomUUID().toString()
                val fecha = Instant.now().toString()

                val pregunta = com.eduracha.models.Pregunta(
                    id = id,
                    cursoId = cursoId,
                    temaId = temaId,
                    texto = ai.texto,
                    opciones = ai.opciones.map {
                        com.eduracha.models.Opcion(
                            id = it.id,
                            texto = it.texto,
                            esCorrecta = it.esCorrecta
                        )
                    },
                    fuente = "ia",
                    estado = "pendiente_revision",
                    dificultad = ai.dificultad,
                    creadoPor = "openai",
                    fechaCreacion = fecha,
                    metadatosIA = com.eduracha.models.MetadatosIA(
                        generadoPor = ai.metadatosIA?.generadoPor,
                        instruccion = ai.metadatosIA?.instruccion
                    )
                )

                newRef.setValueAsync(pregunta).get()
                saved.add(pregunta)
            }

            return saved

        } catch (e: Exception) {
            println("Error al interpretar la respuesta JSON: ${e.message}")
            println("Respuesta cruda de OpenAI:\n$respText")
            throw IllegalStateException("Error al interpretar la respuesta JSON de OpenAI.")
        }
    }
}
