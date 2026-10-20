package com.eduracha.ai

/** Configuración centralizada por variables de entorno. Sin secretos en código. */
data class AiConfig(
    val provider: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String?,
    val timeoutSeconds: Long,
    /** Solo Anthropic: ID del workspace (wrkspc_...) para llaves que no están ligadas a uno. */
    val workspaceId: String? = null
) {
    companion object {
        fun desdeEntorno(env: (String) -> String? = { System.getenv(it) }): AiConfig {
            val provider = (env("AI_PROVIDER") ?: env("OPENAI_API_KEY")?.let { "openai" } ?: "groq")
                .lowercase().trim()
            val model = env("AI_MODEL") ?: when (provider) {
                "groq" -> "openai/gpt-oss-120b"
                "openai" -> "gpt-4o-mini"
                "anthropic" -> "claude-sonnet-5-5"
                "ollama" -> "llama3.1"
                else -> "gpt-4o-mini"
            }
            val apiKey = env("AI_API_KEY") ?: env("OPENAI_API_KEY") ?: env("GROQ_API_KEY") ?: ""
            val baseUrl = env("AI_BASE_URL")
            val timeout = env("AI_TIMEOUT_SECONDS")?.toLongOrNull() ?: 60L
            val workspaceId = env("AI_WORKSPACE_ID")?.takeIf { it.isNotBlank() }
            return AiConfig(provider, model, apiKey, baseUrl, timeout, workspaceId)
        }

        fun validar(cfg: AiConfig) {
            val soportados = setOf("groq", "openai", "anthropic", "openai-compatible", "ollama", "mock")
            require(cfg.provider in soportados) {
                "AI_PROVIDER '${cfg.provider}' no implementado. Soportados: $soportados"
            }
            if (cfg.provider != "ollama" && cfg.provider != "mock") {
                require(cfg.apiKey.isNotBlank()) { "Falta AI_API_KEY para proveedor '${cfg.provider}'" }
            }
        }
    }
}
