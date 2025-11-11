package com.eduracha.services

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.engine.cio.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import com.google.firebase.database.FirebaseDatabase
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds


@Serializable
data class PreguntaAI(
    val texto: String,
    val opciones: List<OpcionAI>,
    val dificultad: String? = null,
    val explicacionCorrecta: String? = null,
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

    // GENERAR EXPLICACIONES
    suspend fun generarExplicacion(
        cursoId: String,
        temaId: String,
        tituloTema: String,
        contenidoTema: String?
    ): String {

        val systemPrompt = """
            Eres un profesor experto en pedagogía. Tu tarea es crear explicaciones claras, 
            estructuradas y didácticas sobre temas educativos.
            
            La explicación debe:
            - Ser clara y fácil de entender
            - Incluir ejemplos prácticos
            - Estar bien estructurada con introducción, desarrollo y conclusión
            - Tener aproximadamente 300-500 palabras
            - Usar un lenguaje apropiado para estudiantes
            - Incluir analogías cuando sea pertinente
            
            Devuelve ÚNICAMENTE el texto de la explicación, sin formato markdown ni títulos adicionales.
        """.trimIndent()

        val userPrompt = if (!contenidoTema.isNullOrBlank()) {
            """
            Crea una explicación completa sobre el tema: "$tituloTema"
            
            Contenido adicional proporcionado:
            $contenidoTema
            
            Por favor genera una explicación didáctica y bien estructurada.
            """.trimIndent()
        } else {
            """
            Crea una explicación completa sobre el tema: "$tituloTema"
            
            Por favor genera una explicación didáctica y bien estructurada basándote en 
            conocimientos generales sobre este tema.
            """.trimIndent()
        }

        val request = ChatRequest(
            model = "gpt-4o-mini",
            messages = listOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", userPrompt)
            ),
            temperature = 0.7,
            max_tokens = 2000
        )
    val client = HttpClient(CIO) {
    engine {
        requestTimeout = 300_000 // 5 minutos (en milisegundos)
        endpoint {
            connectTimeout = 180_000 // 3 minutos
            keepAliveTime = 5000
            connectAttempts = 5
        }
    }
}



        val response = client.post("https://api.openai.com/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $openAiApiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
    
        }

        val respText = response.bodyAsText()

        return try {
            val root = json.parseToJsonElement(respText).jsonObject
            val content = root["choices"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                ?: throw IllegalStateException("No se encontró contenido en la respuesta de OpenAI")

            val explicacion = content.trim()

            // Guardar la explicación en Firebase bajo el nodo "explicaciones"
            val refBase = FirebaseDatabase.getInstance()
                .getReference("explicaciones")
                .child(cursoId)
                .child(temaId)

            val data = mapOf(
                "cursoId" to cursoId,
                "temaId" to temaId,
                "titulo" to tituloTema,
                "explicacion" to explicacion,
                "fuente" to "ia",
                "creadoPor" to "openai",
                "fechaCreacion" to Instant.now().toString()
            )

            refBase.setValueAsync(data).get()

            explicacion

        } catch (e: Exception) {
            println("Error al interpretar la respuesta de OpenAI para explicación: ${e.message}")
            println("Respuesta cruda:\n$respText")
            throw IllegalStateException("Error al generar explicación con IA: ${e.message}")
        }
    }

    // GENERAR PREGUNTAS
    suspend fun generarYGuardarPreguntas(
        cursoId: String,
        temaId: String,
        temaTexto: String,
        cantidad: Int = 5
    ): List<com.eduracha.models.Pregunta> {

        val systemPrompt = """
           Eres un generador de preguntas educativas.
    Devuelve únicamente un JSON válido con esta estructura exacta:

    Reglas estrictas:
    - Cada pregunta debe tener una dificultad y SOLO puede ser: "facil", "medio" o "dificil".
    - Alterna las dificultades en las preguntas (no las pongas todas iguales).
    - No incluyas texto adicional, explicaciones ni markdown.
    - El campo "explicacionCorrecta" debe explicar brevemente por qué la respuesta correcta lo es.
     
         FORMATO DE RESPUESTA:: 
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
                  "explicacionCorrecta": "La fotosíntesis es el proceso mediante el cual las plantas producen energía a partir de la luz solar.",
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
            max_tokens = 4000
        )
 val client = HttpClient(CIO) {
    engine {
        requestTimeout = 300_000 // 5 minutos (en milisegundos)
        endpoint {
            connectTimeout = 180_000 // 3 minutos
            keepAliveTime = 5000
            connectAttempts = 5
        }
    }
}




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
                    ),
                    explicacionCorrecta = ai.explicacionCorrecta
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
