package com.eduracha.utils

import java.security.MessageDigest

/** Utilidades para el texto de las explicaciones. */
object TextoPlano {

    /** Quita marcas de Markdown (negritas, títulos, viñetas con *, código) conservando los párrafos. */
    fun sinMarkdown(texto: String): String =
        texto.replace("\r\n", "\n")
            .lines()
            .joinToString("\n") { linea ->
                linea.trimEnd()
                    .replace(Regex("^\\s*#{1,6}\\s*"), "")
                    .replace(Regex("^(\\s*)[*+]\\s+"), "$1- ")
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "")
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Huella corta del contenido, para saber si una explicación cambió. Ignora espacios y Markdown. */
    fun huella(texto: String?): String? {
        if (texto.isNullOrBlank()) return null
        val normal = sinMarkdown(texto).replace(Regex("\\s+"), " ").trim().lowercase()
        val bytes = MessageDigest.getInstance("SHA-256").digest(normal.toByteArray(Charsets.UTF_8))
        return bytes.take(12).joinToString("") { "%02x".format(it) }
    }
}
