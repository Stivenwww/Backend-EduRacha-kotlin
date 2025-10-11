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
    suspend fun crearPregunta(pregunta: Pregunta): String = suspendCancellableCoroutine { cont ->
        val nuevoRef = ref.push()
        val id = nuevoRef.key ?: return@suspendCancellableCoroutine cont.resumeWithException(Exception("No se pudo generar ID"))
        val nuevaPregunta = pregunta.copy(id = id)

        nuevoRef.setValue(nuevaPregunta, DatabaseReference.CompletionListener { error, _ ->
            if (error != null) cont.resumeWithException(Exception(error.message))
            else cont.resume(id)
        })
    }

    // Obtener todas las preguntas
    suspend fun obtenerPreguntas(): List<Pregunta> = suspendCancellableCoroutine { cont ->
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

    // Obtener preguntas por curso
    suspend fun obtenerPreguntasPorCurso(cursoId: String): List<Pregunta> = suspendCancellableCoroutine { cont ->
        ref.orderByChild("cursoId").equalTo(cursoId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val lista = snapshot.children.mapNotNull { it.getValue(Pregunta::class.java) }
                    cont.resume(lista)
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }

    // Obtener pregunta por ID
    suspend fun obtenerPreguntaPorId(id: String): Pregunta? = suspendCancellableCoroutine { cont ->
        ref.child(id).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                cont.resume(snapshot.getValue(Pregunta::class.java))
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }

    // Actualizar pregunta existente
    suspend fun actualizarPregunta(id: String, pregunta: Pregunta) = suspendCancellableCoroutine<Unit> { cont ->
        ref.child(id).setValue(pregunta.copy(id = id), DatabaseReference.CompletionListener { error, _ ->
            if (error != null) cont.resumeWithException(Exception(error.message))
            else cont.resume(Unit)
        })
    }

    // Eliminar pregunta
    suspend fun eliminarPregunta(id: String) = suspendCancellableCoroutine<Unit> { cont ->
        ref.child(id).removeValue(DatabaseReference.CompletionListener { error, _ ->
            if (error != null) cont.resumeWithException(Exception(error.message))
            else cont.resume(Unit)
        })
    }

    // Obtener preguntas pendientes de revisión generadas por IA
    suspend fun obtenerPreguntasPendientes(cursoId: String, temaId: String): List<Pregunta> = suspendCancellableCoroutine { cont ->
        val refPendientes = database.getReference("preguntas/pendientes/$cursoId/$temaId")

        refPendientes.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lista = snapshot.children.mapNotNull { it.getValue(Pregunta::class.java) }
                cont.resume(lista)
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }

    // Revisar una pregunta generada por IA (aprobar o rechazar)
    suspend fun revisarPreguntaIA(id: String, estado: String, notas: String) = suspendCancellableCoroutine<Unit> { cont ->
        val refPendientes = database.getReference("preguntas/pendientes")

        refPendientes.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var encontrada = false

                snapshot.children.forEach { curso ->
                    curso.children.forEach { tema ->
                        tema.children.forEach { preguntaNode ->
                            val pregunta = preguntaNode.getValue(Pregunta::class.java)
                            if (pregunta?.id == id) {
                                encontrada = true
                                if (estado == "aprobada") {
                                    // Mover la pregunta al banco general de preguntas aprobadas
                                    val refBanco = database.getReference("banco_preguntas")
                                        .child(pregunta.cursoId)
                                        .child(pregunta.temaId)
                                        .child(id)

                                    refBanco.setValue(
                                        pregunta.copy(estado = "aprobada"),
                                        DatabaseReference.CompletionListener { error, _ ->
                                            if (error != null) cont.resumeWithException(Exception(error.message))
                                            else {
                                                preguntaNode.ref.removeValue(DatabaseReference.CompletionListener { err, _ ->
                                                    if (err != null) cont.resumeWithException(Exception(err.message))
                                                    else cont.resume(Unit)
                                                })
                                            }
                                        }
                                    )
                                } else {
                                    // Marcar pregunta como rechazada y guardar observaciones
                                    val updates = mapOf(
                                        "estado" to "rechazada",
                                        "notasRevision" to notas
                                    )
                                    preguntaNode.ref.updateChildren(updates, DatabaseReference.CompletionListener { error, _ ->
                                        if (error != null) cont.resumeWithException(Exception(error.message))
                                        else cont.resume(Unit)
                                    })
                                }
                                return
                            }
                        }
                    }
                }

                if (!encontrada) cont.resumeWithException(Exception("Pregunta con ID $id no encontrada en pendientes"))
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }
}
