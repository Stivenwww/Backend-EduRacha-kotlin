package com.eduracha.ai

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
private data class AnthropicMsg(val role: String, val content: String)

@Serializable
private data class AnthropicReq(
    val model: String,
    val system: String,
    val messages: List<AnthropicMsg>,
    val temperature: Double = 0.2,
    val max_tokens: Int = 2000
)

/** Adaptador Anthropic Claude (Messages API, x-api-key + anthropic-version). */
class AnthropicProvider(
    private val client: HttpClient,
    private val model: String,
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com/v1",
    private val workspaceId: String? = null
) : AiProvider {
    override val nombre = "anthropic"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chat(req: AiChatRequest): AiChatResponse {
        require(req.systemPrompt.isNotBlank() && req.userPrompt.isNotBlank()) { "Prompt vacío" }
        val body = AnthropicReq(model, req.systemPrompt.take(12_000), listOf(AnthropicMsg("user", req.userPrompt.take(12_000))), req.temperature, req.maxTokens)
        val resp = try {
            client.post("$baseUrl/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                if (!workspaceId.isNullOrBlank()) header("anthropic-workspace-id", workspaceId)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(body))
            }.bodyAsText()
        } catch (e: Exception) {
            throw IllegalStateException("Error de red con Anthropic: ${e.message}")
        }
        val content = try {
            val root = json.parseToJsonElement(resp).jsonObject
            root["error"]?.let { err ->
                val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: err.toString()
                throw IllegalStateException("El proveedor $nombre rechazó la solicitud: $msg")
            }
            root["content"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content?.trim()
                ?: throw IllegalStateException("Sin 'content[0].text'. Resp: ${resp.take(300)}")
        } catch (e: IllegalStateException) { throw e }
        catch (e: Exception) { throw IllegalStateException("Respuesta inválida de Anthropic: ${e.message}") }
        return AiChatResponse(content, nombre, model)
    }
}

/** Proveedor simulado para pruebas (sin red, sin costo). */
class MockAiProvider(private val respuesta: String = "{\"preguntas\":[]}") : AiProvider {
    override val nombre = "mock"
    override suspend fun chat(req: AiChatRequest) = AiChatResponse(respuesta, nombre, "mock")
}
