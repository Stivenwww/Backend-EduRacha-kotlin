package com.eduracha.utils

import io.github.cdimascio.dotenv.dotenv
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL

object OpenAIClient {
    private val dotenv = dotenv()
    private val apiKey = dotenv["OPENAI_API_KEY"]

    suspend fun generarRespuesta(prompt: String): String {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
        }

        val requestBody = """
            {
              "model": "gpt-4o-mini",
              "messages": [
                {"role": "system", "content": "Eres un asistente educativo de EduRacha."},
                {"role": "user", "content": "$prompt"}
              ]
            }
        """.trimIndent()

        connection.outputStream.use { it.write(requestBody.toByteArray()) }

        val response = connection.inputStream.bufferedReader().readText()
        val json = Json.parseToJsonElement(response).jsonObject

        val content = json["choices"]
            ?.jsonArray?.getOrNull(0)
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
            ?.toString() ?: "Sin respuesta"

        return content.trim('"')
    }
}
