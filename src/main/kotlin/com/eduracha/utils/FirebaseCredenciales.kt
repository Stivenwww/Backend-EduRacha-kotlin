package com.eduracha.utils

import com.google.auth.oauth2.GoogleCredentials
import java.io.File
import java.util.Base64

/**
 * Carga las credenciales de Firebase de forma tolerante.
 * Acepta (en orden):
 *  1. JSON crudo del service-account (empieza con '{').
 *  2. Base64 estándar (con '+', '/', '=').
 *  3. Base64 URL-safe (con '-', '_').
 *  4. Ruta a un archivo .json existente (relativa o absoluta).
 *  5. Fallback de desarrollo: src/main/resources/serviceAccountKey.json si existe.
 *
 * Nunca imprime el contenido del secreto, solo metadatos (longitud, formato).
 */
object FirebaseCredenciales {

    fun cargar(valorEnv: String?): GoogleCredentials {
        if (valorEnv.isNullOrBlank()) {
            // 5. Desarrollo local sin variable: usar el archivo del proyecto si existe
            val local = File("src/main/resources/serviceAccountKey.json")
            if (local.isFile && local.length() > 500) {
                println("Credenciales Firebase cargadas desde archivo: ${local.path} (${local.length()} bytes)")
                return GoogleCredentials.fromStream(local.inputStream())
            }
        }
        val raw = valorEnv?.trim()?.trim('"', '\'')?.replace("\\s".toRegex(), "")
            ?: throw IllegalStateException(
                "Falta FIREBASE_CREDENTIALS_BASE64. " +
                    "Genera el valor con: [Convert]::ToBase64String([IO.File]::ReadAllBytes(\"<ruta-service-account.json>\"))"
            )

        // 1. JSON crudo
        if (raw.trimStart().startsWith("{")) {
            return GoogleCredentials.fromStream(raw.byteInputStream())
        }

        // 2-3. Base64 estándar, URL-safe o MIME (ignora saltos de línea)
        val decoders = listOf(
            "estándar" to Base64.getDecoder(),
            "URL-safe" to Base64.getUrlDecoder(),
            "MIME" to Base64.getMimeDecoder()
        )
        for ((nombre, decoder) in decoders) {
            try {
                val bytes = decoder.decode(raw)
                // Un service-account real pesa > 500 bytes; si es diminuto, no es la credencial
                if (bytes.size > 500) {
                    println("Credenciales Firebase cargadas (base64 $nombre, ${bytes.size} bytes)")
                    return GoogleCredentials.fromStream(bytes.inputStream())
                }
            } catch (_: IllegalArgumentException) {
                // probar siguiente formato
            }
        }

        // 4. ¿Es una ruta a un archivo?
        val comoRuta = File(valorEnv.trim().trim('"', '\''))
        val candidatos = listOf(
            comoRuta,
            File("src/main/resources/serviceAccountKey.json"),
            File("src/main/resources/firebase-service-account.example.json")
        )
        val archivo = candidatos.firstOrNull { it.exists() && it.isFile && it.length() > 500 }
        if (archivo != null) {
            println("Credenciales Firebase cargadas desde archivo: ${archivo.path} (${archivo.length()} bytes)")
            return GoogleCredentials.fromStream(archivo.inputStream())
        }

        throw IllegalStateException(
            "FIREBASE_CREDENTIALS_BASE64 inválido (longitud ${raw.length}; se esperan >2000 caracteres base64). " +
                "Regenera el valor con PowerShell: " +
                "[Convert]::ToBase64String([IO.File]::ReadAllBytes(\"<ruta-service-account.json>\")) " +
                "y pégalo en una sola línea sin comillas ni espacios."
        )
    }
}
