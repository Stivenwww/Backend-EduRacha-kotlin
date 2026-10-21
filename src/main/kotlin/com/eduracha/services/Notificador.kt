package com.eduracha.services

import com.google.firebase.database.FirebaseDatabase

/**
 * Avisos en tiempo real para estudiantes y docentes.
 * Se guardan en notificaciones/{uid}/{id}; la app los escucha y los muestra en su panel
 * (campana con contador + aviso en pantalla cuando llega uno nuevo).
 * Nunca lanza excepciones: si falla, solo lo registra, para no romper la acción principal.
 */
object Notificador {

    /** Arma el documento que guarda Firebase (sin nulos y con la fecha en milisegundos). */
    fun documento(titulo: String, mensaje: String, tipo: String, datos: Map<String, Any?> = emptyMap(), ahora: Long = System.currentTimeMillis()): Map<String, Any> {
        val doc = mutableMapOf<String, Any>(
            "titulo" to titulo.take(120),
            "mensaje" to mensaje.take(400),
            "tipo" to tipo,
            "fecha" to ahora,
            "leido" to false
        )
        datos.forEach { (k, v) -> if (v != null && v.toString().isNotBlank()) doc[k] = v }
        return doc
    }

    fun enviar(uid: String?, titulo: String, mensaje: String, tipo: String, datos: Map<String, Any?> = emptyMap()) {
        if (uid.isNullOrBlank()) return
        try {
            FirebaseDatabase.getInstance().getReference("notificaciones").child(uid).push()
                .setValue(documento(titulo, mensaje, tipo, datos)) { error, _ ->
                    if (error != null) println("Notificador: no se pudo avisar a $uid (${error.message})")
                }
        } catch (e: Exception) {
            println("Notificador: ${e.message}")
        }
    }

    fun enviarAVarios(uids: Collection<String>, titulo: String, mensaje: String, tipo: String, datos: Map<String, Any?> = emptyMap()) {
        uids.filter { it.isNotBlank() }.toSet().forEach { enviar(it, titulo, mensaje, tipo, datos) }
    }
}
