package com.eduracha.repository

import com.eduracha.models.Tema
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExplicacionRepository {

    private val database = FirebaseDatabase.getInstance()
    private val ref = database.getReference("cursos")

    suspend fun obtenerTema(cursoId: String, temaId: String): Tema? =
        suspendCancellableCoroutine { cont ->
            ref.child(cursoId).child("temas").child(temaId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        cont.resume(snapshot.getValue(Tema::class.java))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    suspend fun actualizarExplicacion(
        cursoId: String,
        temaId: String,
        explicacion: String,
        fuente: String,
        estado: String = "pendiente"
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val refTema = ref.child(cursoId).child("temas").child(temaId)
        val timestamp = Instant.now().toString()

        val updates = mapOf(
            "explicacion" to explicacion,
            "explicacionFuente" to fuente,
            "explicacionUltimaActualizacion" to timestamp,
            "explicacionEstado" to estado
        )

        refTema.updateChildren(updates, DatabaseReference.CompletionListener { error, _ ->
            if (error != null) {
                cont.resumeWithException(Exception(error.message))
            } else {
                cont.resume(Unit)
            }
        })
    }

    suspend fun actualizarEstadoExplicacion(
        cursoId: String,
        temaId: String,
        nuevoEstado: String
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val refTema = ref.child(cursoId).child("temas").child(temaId)

        refTema.updateChildren(
            mapOf("explicacionEstado" to nuevoEstado),
            DatabaseReference.CompletionListener { error, _ ->
                if (error != null) {
                    cont.resumeWithException(Exception(error.message))
                } else {
                    cont.resume(Unit)
                }
            })
    }

    suspend fun obtenerTemasConExplicaciones(cursoId: String): List<Tema> =
        suspendCancellableCoroutine { cont ->
            ref.child(cursoId).child("temas")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val temas = snapshot.children.mapNotNull { 
                            it.getValue(Tema::class.java) 
                        }
                        cont.resume(temas)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    suspend fun obtenerTemasPorEstadoExplicacion(
        cursoId: String,
        estado: String
    ): List<Tema> = suspendCancellableCoroutine { cont ->
        ref.child(cursoId).child("temas")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val temas = snapshot.children.mapNotNull { 
                        it.getValue(Tema::class.java) 
                    }.filter { tema ->
                        tema.explicacionEstado.equals(estado, ignoreCase = true)
                    }
                    cont.resume(temas)
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }

    suspend fun tieneExplicacionAprobada(cursoId: String, temaId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            ref.child(cursoId).child("temas").child(temaId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val tema = snapshot.getValue(Tema::class.java)
                        val aprobada = tema?.explicacionEstado == "aprobada" && 
                                      !tema.explicacion.isNullOrBlank()
                        cont.resume(aprobada)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }
}