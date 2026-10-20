package com.eduracha.utils

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.coroutines.resumeWithException

/**
 * Lecturas/escrituras seguras contra Firebase Realtime Database.
 *
 * Problema que resuelve: los ValueEventListener nunca llamaban de vuelta cuando
 * (a) Firebase no respondía (credenciales/URL/red) o (b) getValue() lanzaba una
 * excepción por un dato con tipo inesperado. En ambos casos la corrutina quedaba
 * suspendida para siempre y la app se quedaba "cargando" hasta su propio timeout.
 */

private val log = LoggerFactory.getLogger("FirebaseSeguro")

/** Tiempo máximo de espera de una operación de Firebase (ms). Configurable con FIREBASE_TIMEOUT_MS. */
val TIMEOUT_FIREBASE_MS: Long = System.getenv("FIREBASE_TIMEOUT_MS")?.toLongOrNull() ?: 25_000L

class FirebaseTimeoutException(mensaje: String) : Exception(mensaje)

/** Igual que suspendCancellableCoroutine, pero falla con un error claro si Firebase no responde a tiempo. */
suspend inline fun <T> lecturaFirebase(crossinline block: (CancellableContinuation<T>) -> Unit): T =
    try {
        withTimeout(TIMEOUT_FIREBASE_MS) {
            suspendCancellableCoroutine<T> { c -> block(c) }
        }
    } catch (e: TimeoutCancellationException) {
        throw FirebaseTimeoutException("Firebase no respondió en ${TIMEOUT_FIREBASE_MS} ms")
    }

/** Registra el error y reanuda la corrutina con la excepción (si aún está activa). */
fun <T> reanudarConError(cont: CancellableContinuation<T>, e: Throwable, origen: String) {
    log.error("Error procesando datos de Firebase en $origen: ${e.message}", e)
    if (cont.isActive) cont.resumeWithException(e)
}
