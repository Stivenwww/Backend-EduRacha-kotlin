package com.eduracha.ai

import kotlinx.serialization.Serializable

@Serializable
data class AiChatMessage(val role: String, val content: String)

data class AiChatRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val temperature: Double = 0.2,
    val maxTokens: Int = 2000
)

data class AiChatResponse(val content: String, val provider: String, val model: String)

/** Contrato común. Los servicios de negocio solo dependen de esta interfaz. */
interface AiProvider {
    val nombre: String
    suspend fun chat(req: AiChatRequest): AiChatResponse
}
