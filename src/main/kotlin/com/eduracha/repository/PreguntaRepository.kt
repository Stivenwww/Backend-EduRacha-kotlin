package com.eduracha.repository

import com.eduracha.models.Pregunta
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PreguntaRepository {

    private val database = FirebaseDatabase.getInstance()
    private val ref = database.getReference("preguntas")

    // Crear una nueva pregunta
    suspend fun crearPregunta(pregunta: Pregunta): String =
        suspendCancellableCoroutine { cont ->
            val nuevoRef = ref.push()
            val id = nuevoRef.key ?: return@suspendCancellableCoroutine cont.resumeWithException(
                Exception("No se pudo generar ID")
            )

            val nuevaPregunta = pregunta.copy(
                id = id,
                estado = if (pregunta.estado.isNotEmpty()) pregunta.estado else "pendiente_revision",
                explicacionCorrecta = pregunta.explicacionCorrecta ?: ""
            )

            nuevoRef.setValue(nuevaPregunta, DatabaseReference.CompletionListener { error, _ ->
                if (error != null) cont.resumeWithException(Exception(error.message))
                else cont.resume(id)
            })
        }

    // Obtener todas las preguntas
    suspend fun obtenerPreguntas(): List<Pregunta> =
        suspendCancellableCoroutine { cont ->
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val lista = snapshot.children.mapNotNull { it.getValue(Pregunta::class.java) }
                    cont.resume(lista)
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
        }

    // Obtener preguntas por curso y estado
    suspend fun obtenerPreguntasPorCursoYEstado(cursoId: String, estado: String?): List<Pregunta> =
        suspendCancellableCoroutine { cont ->
            ref.orderByChild("cursoId").equalTo(cursoId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val todas = snapshot.children.mapNotNull { it.getValue(Pregunta::class.java) }

                        val filtradas = when {
                            estado.isNullOrBlank() ||
                            estado.equals("todos", ignoreCase = true) -> todas
                            else -> todas.filter { it.estado.equals(estado, ignoreCase = true) }
                        }

                        cont.resume(filtradas)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    // Obtener una pregunta por ID
    suspend fun obtenerPreguntaPorId(id: String): Pregunta? =
        suspendCancellableCoroutine { cont ->
            ref.child(id).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    cont.resume(snapshot.getValue(Pregunta::class.java))
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
        }

      // Actualizar toda la pregunta
    suspend fun actualizarPregunta(id: String, pregunta: Pregunta) =
        suspendCancellableCoroutine<Unit> { cont ->
            ref.child(id).setValue(pregunta.copy(id = id), DatabaseReference.CompletionListener { error, _ ->
                if (error != null) cont.resumeWithException(Exception(error.message))
                else cont.resume(Unit)
            })
        }


    // Actualizar solo el estado de la pregunta
    suspend fun actualizarEstadoPregunta(id: String, nuevoEstado: String, notas: String? = null) =
        suspendCancellableCoroutine<Unit> { cont ->
            val updates = mutableMapOf<String, Any>("estado" to nuevoEstado)
            notas?.let { updates["notasRevision"] = it }

            ref.child(id).updateChildren(updates, DatabaseReference.CompletionListener { error, _ ->
                if (error != null) cont.resumeWithException(Exception(error.message))
                else cont.resume(Unit)
            })
        }

    // Eliminar una pregunta
    suspend fun eliminarPregunta(id: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            ref.child(id).removeValue(DatabaseReference.CompletionListener { error, _ ->
                if (error != null) cont.resumeWithException(Exception(error.message))
                else cont.resume(Unit)
            })
        }

    // Borrar caché local de Firebase
    fun limpiarCacheFirebase() {
        database.purgeOutstandingWrites()
        ref.keepSynced(false)
    }
}

