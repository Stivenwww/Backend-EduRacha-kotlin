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

    // Obtener un tema específico
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

    // Actualizar explicación (texto, fuente y estado)
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
            if (error != null) cont.resumeWithException(Exception(error.message))
            else cont.resume(Unit)
        })
    }

    // Actualizar solo el estado de la explicación
    suspend fun actualizarEstadoExplicacion(
        cursoId: String,
        temaId: String,
        nuevoEstado: String
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val refTema = ref.child(cursoId).child("temas").child(temaId)
        refTema.updateChildren(
            mapOf("explicacionEstado" to nuevoEstado),
            DatabaseReference.CompletionListener { error, _ ->
                if (error != null) cont.resumeWithException(Exception(error.message))
                else cont.resume(Unit)
            })
    }

    // Obtener todos los temas con sus explicaciones (sin filtro)
    suspend fun obtenerTemasConExplicaciones(cursoId: String): List<Tema> =
        suspendCancellableCoroutine { cont ->
            ref.child(cursoId).child("temas")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val temas = snapshot.children.mapNotNull { it.getValue(Tema::class.java) }
                        cont.resume(temas)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    // obtener temas filtrando explicación (solo muestra si está aprobada)
    suspend fun obtenerTemasFiltrandoExplicacion(cursoId: String): List<Tema> =
        suspendCancellableCoroutine { cont ->
            ref.child(cursoId).child("temas")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val temas = snapshot.children.mapNotNull { it.getValue(Tema::class.java) }
                            .map { tema ->
                                // Si la explicación no está aprobada, la ocultamos
                                if (tema.explicacionEstado != "aprobada") {
                                    tema.copy(explicacion = null)
                                } else tema
                            }
                        cont.resume(temas)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    //  obtener temas por estado específico
    suspend fun obtenerTemasPorEstadoExplicacion(cursoId: String, estado: String): List<Tema> =
        suspendCancellableCoroutine { cont ->
            ref.child(cursoId).child("temas")
                .orderByChild("explicacionEstado").equalTo(estado)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val temas = snapshot.children.mapNotNull { it.getValue(Tema::class.java) }
                        cont.resume(temas)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    // Verificar si un tema tiene explicación aprobada y no vacía
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
