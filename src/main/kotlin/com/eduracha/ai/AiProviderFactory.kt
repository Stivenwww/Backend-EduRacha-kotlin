package com.eduracha.ai

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*

/** Fábrica centralizada. Selección solo por AI_PROVIDER. Sin fallback facturable implícito. */
object AiProviderFactory {
    fun crear(cfg: AiConfig, client: HttpClient? = null): AiProvider {
        AiConfig.validar(cfg)
        val http = client ?: HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = cfg.timeoutSeconds * 1000
                connectTimeoutMillis = minOf(cfg.timeoutSeconds * 1000, 30_000)
            }
        }
        val base = when (cfg.provider) {
            "groq" -> GroqProvider(http, cfg.model, cfg.apiKey, cfg.baseUrl)
            "openai" -> OpenAiProvider(http, cfg.model, cfg.apiKey, cfg.baseUrl)
            "anthropic" -> AnthropicProvider(http, cfg.model, cfg.apiKey, cfg.baseUrl ?: "https://api.anthropic.com/v1", cfg.workspaceId)
            "openai-compatible" -> {
                val url = requireNotNull(cfg.baseUrl) { "AI_BASE_URL requerido para openai-compatible" }
                OpenAiCompatibleProvider(http, cfg.model, cfg.apiKey, url)
            }
            "ollama" -> OllamaProvider(http, cfg.model, cfg.baseUrl)
            "mock" -> MockAiProvider()
            else -> throw IllegalArgumentException("Proveedor no implementado: ${cfg.provider}")
        }
        // Reintentos ante "límite superado" y máximo 2 llamadas simultáneas a la IA por servidor
        return if (cfg.provider == "mock") base else ProveedorConReintentos(base)
    }
}
