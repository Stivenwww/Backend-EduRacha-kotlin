package com.eduracha.utils

import io.github.cdimascio.dotenv.dotenv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object OpenAIClient {
    private val dotenv = dotenv()
    private val apiKey = dotenv["OPENAI_API_KEY"]
        ?: throw IllegalStateException("Falta la variable OPENAI_API_KEY en el archivo .env")

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generarRespuesta(prompt: String): String {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true

            connectTimeout = 180_000   // 3 minutos para conectar
            readTimeout = 300_000      // 5 minutos para recibir la respuesta completa
        }

        // Cuerpo del request al modelo GPT-4o-mini
        val requestBody = """
            {
              "model": "gpt-4o-mini",
              "messages": [
                {"role": "system", "content": "Eres un asistente educativo experto de EduRacha. Explica y genera contenido de forma clara y precisa."},
                {"role": "user", "content": "$prompt"}
              ],
              "temperature": 0.7,
              "max_tokens": 2000
            }
        """.trimIndent()

        try {
            // Enviar cuerpo JSON
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            // Leer respuesta
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val root = json.parseToJsonElement(response).jsonObject

            val content = root["choices"]
                ?.jsonArray?.getOrNull(0)
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.toString()
                ?: "Sin respuesta"

            // Limpiar posibles comillas extras o saltos
            return content.trim('"', ' ', '\n')

        } catch (e: Exception) {
            val errorMsg = "Error al comunicarse con OpenAI: ${e.message}"
            println(errorMsg)
            throw IllegalStateException(errorMsg)
        } finally {
            connection.disconnect()
        }
    }
}
