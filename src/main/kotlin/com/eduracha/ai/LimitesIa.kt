package com.eduracha.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * El proveedor respondió que se superó su límite de uso (HTTP 429) o que está saturado (502/503).
 * [esperaSegundos] es lo que el proveedor pidió esperar, si lo dijo.
 */
class LimiteIaException(mensaje: String, val esperaSegundos: Double?) : IllegalStateException(mensaje)

object LimitesIa {
    /** Lee "retry-after" (segundos) o el "try again in 7.5s" / "in 1m2.5s" que Groq y OpenAI ponen en el mensaje. */
    fun esperaSugerida(retryAfter: String?, mensaje: String?): Double? {
        retryAfter?.trim()?.toDoubleOrNull()?.let { return it }
        val m = mensaje ?: return null
        Regex("""in\s+(?:(\d+)m)?\s*([\d.]+)\s*(ms|s)\b""", RegexOption.IGNORE_CASE).find(m)?.let { r ->
            val min = r.groupValues[1].toDoubleOrNull() ?: 0.0
            val valor = r.groupValues[2].toDoubleOrNull() ?: return null
            val seg = if (r.groupValues[3].equals("ms", true)) valor / 1000.0 else valor
            return min * 60 + seg
        }
        return null
    }

    /** Espera antes del intento [intento] (1, 2, 3…): lo que pidió el proveedor o un retroceso exponencial. */
    fun esperaMs(intento: Int, sugerida: Double?): Long {
        val base = sugerida?.let { (it * 1000).toLong() + 500 } ?: (2_000L shl (intento - 1).coerceAtMost(4))
        return base.coerceIn(1_000L, 65_000L)
    }
}

/**
 * Envuelve a cualquier proveedor para que el servidor aguante ráfagas (por ejemplo, 30 preguntas en lotes):
 *  - como máximo [concurrencia] llamadas a la vez a la IA desde todo el servidor;
 *  - si el proveedor responde "límite superado", espera lo que pide y reintenta hasta [intentos] veces.
 * Los demás errores (llave inválida, JSON malo) no se reintentan.
 */
class ProveedorConReintentos(
    private val base: AiProvider,
    private val intentos: Int = 4,
    concurrencia: Int = 2,
    /** Tiempo máximo esperando al proveedor en una misma llamada, para no superar el tiempo de espera de la app. */
    private val esperaMaximaMs: Long = 100_000,
    private val dormir: suspend (Long) -> Unit = { delay(it) }
) : AiProvider {
    override val nombre: String get() = base.nombre
    private val cupo = Semaphore(concurrencia)

    override suspend fun chat(req: AiChatRequest): AiChatResponse = cupo.withPermit {
        var intento = 1
        var esperado = 0L
        while (true) {
            try {
                return@withPermit base.chat(req)
            } catch (e: LimiteIaException) {
                val ms = LimitesIa.esperaMs(intento, e.esperaSegundos)
                if (intento >= intentos || esperado + ms > esperaMaximaMs) {
                    throw IllegalStateException(
                        "La IA está recibiendo demasiadas solicitudes en este momento. Espera un minuto e inténtalo de nuevo."
                    )
                }
                println("IA ${base.nombre}: límite alcanzado, reintento $intento en ${ms} ms")
                dormir(ms)
                esperado += ms
                intento++
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }
}
