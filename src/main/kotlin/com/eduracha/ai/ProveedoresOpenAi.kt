package com.eduracha.ai

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
private data class OAIMessage(val role: String, val content: String)

@Serializable
private data class OAIRequest(
    val model: String,
    val messages: List<OAIMessage>,
    val temperature: Double = 0.2,
    val max_tokens: Int = 2000
)

/**
 * Base para APIs compatibles con OpenAI (OpenAI, Groq, Ollama, compatibles).
 * Valida tamaño de prompt, maneja timeout/errores y parsea content.
 */
open class BaseOpenAiCompatibleProvider(
    override val nombre: String,
    protected val client: HttpClient,
    protected val model: String,
    protected val apiKey: String,
    protected val baseUrl: String,
) : AiProvider {
    protected val json = Json { ignoreUnknownKeys = true }
    companion object { const val MAX_PROMPT_CHARS = 12_000 }

    /** Los modelos que razonan (gpt-oss, qwen3, deepseek-r1) gastan tokens pensando antes de responder. */
    private fun tokensMaximos(pedidos: Int): Int {
        val m = model.lowercase()
        val razona = m.contains("gpt-oss") || m.contains("qwen3") || m.contains("-r1") || m.startsWith("o1") || m.startsWith("o3")
        return if (razona) maxOf(pedidos, 8000) else pedidos
    }

    override suspend fun chat(req: AiChatRequest): AiChatResponse {
        val system = req.systemPrompt.take(MAX_PROMPT_CHARS)
        val user = req.userPrompt.take(MAX_PROMPT_CHARS)
        require(system.isNotBlank() && user.isNotBlank()) { "Prompt vacío" }
        val body = OAIRequest(model, listOf(OAIMessage("system", system), OAIMessage("user", user)), req.temperature, tokensMaximos(req.maxTokens))
        val http: HttpResponse = try {
            client.post("$baseUrl/chat/completions") {
                if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(body))
            }
        } catch (e: Exception) {
            throw IllegalStateException("Error de red con proveedor $nombre: ${e.message}")
        }
        val resp = http.bodyAsText()
        val codigo = http.status.value
        if (codigo == 429 || codigo == 502 || codigo == 503) {
            val msg = runCatching {
                json.parseToJsonElement(resp).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: resp.take(200)
            throw LimiteIaException("Límite del proveedor $nombre ($codigo): $msg", LimitesIa.esperaSugerida(http.headers["retry-after"], msg))
        }
        val content = try {
            val root = json.parseToJsonElement(resp).jsonObject
            root["error"]?.let { err ->
                val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
                throw IllegalStateException("El proveedor $nombre rechazó la solicitud: $msg")
            }
            root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")
                ?.jsonPrimitive?.content?.trim()
                ?: throw IllegalStateException("Sin 'choices[0].message.content'. Resp: ${resp.take(300)}")
        } catch (e: IllegalStateException) { throw e }
        catch (e: Exception) { throw IllegalStateException("Respuesta inválida de $nombre: ${e.message}") }
        return AiChatResponse(content, nombre, model)
    }
}

class OpenAiProvider(client: HttpClient, model: String, apiKey: String, baseUrl: String? = null) :
    BaseOpenAiCompatibleProvider("openai", client, model, apiKey, baseUrl ?: "https://api.openai.com/v1")

class GroqProvider(client: HttpClient, model: String, apiKey: String, baseUrl: String? = null) :
    BaseOpenAiCompatibleProvider("groq", client, model, apiKey, baseUrl ?: "https://api.groq.com/openai/v1")

class OpenAiCompatibleProvider(client: HttpClient, model: String, apiKey: String, baseUrl: String) :
    BaseOpenAiCompatibleProvider("openai-compatible", client, model, apiKey, baseUrl)

class OllamaProvider(client: HttpClient, model: String, baseUrl: String? = null) :
    BaseOpenAiCompatibleProvider("ollama", client, model, "", baseUrl ?: "http://localhost:11434/v1")
