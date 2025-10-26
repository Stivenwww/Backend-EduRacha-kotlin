package com.eduracha.repository

import com.eduracha.models.Curso
import com.eduracha.models.Tema
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CursoRepository {
    private val database = FirebaseDatabase.getInstance()
    private val ref = database.getReference("cursos")

    suspend fun crearCurso(curso: Curso): String {
        val nuevoRef = ref.push()
        val id = nuevoRef.key ?: throw Exception("No se pudo generar ID")
        val nuevoCurso = curso.copy(id = id)
        nuevoRef.setValueAsync(nuevoCurso).get()
        return id
    }

    suspend fun obtenerCursos(): List<Curso> = suspendCancellableCoroutine { cont ->
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lista = snapshot.children.mapNotNull { it.getValue(Curso::class.java) }
                cont.resume(lista)
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }

    suspend fun obtenerCursoPorId(id: String): Curso? = suspendCancellableCoroutine { cont ->
        ref.child(id).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                cont.resume(snapshot.getValue(Curso::class.java))
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }

    suspend fun actualizarCurso(id: String, curso: Curso) {
        ref.child(id).setValueAsync(curso.copy(id = id)).get()
    }

    suspend fun eliminarCurso(id: String) {
        ref.child(id).removeValueAsync().get()
    }

    // Agregar un tema al curso
    suspend fun agregarTema(cursoId: String, tema: Tema): String = 
        suspendCancellableCoroutine { cont ->
            val refTemas = ref.child(cursoId).child("temas")
            val nuevoTemaRef = refTemas.push()
            val temaId = nuevoTemaRef.key ?: return@suspendCancellableCoroutine cont.resumeWithException(
                Exception("No se pudo generar ID del tema")
            )

            val temaConId = tema.copy(
                id = temaId,
                fechaCreacion = Instant.now().toString()
            )

            nuevoTemaRef.setValue(temaConId, DatabaseReference.CompletionListener { error, _ ->
                if (error != null) cont.resumeWithException(Exception(error.message))
                else cont.resume(temaId)
            })
        }

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

    // Obtener todos los temas de un curso
    suspend fun obtenerTemasPorCurso(cursoId: String): List<Tema> = 
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

    // Actualizar un tema completo
    suspend fun actualizarTema(cursoId: String, temaId: String, tema: Tema) = 
        suspendCancellableCoroutine<Unit> { cont ->
            ref.child(cursoId).child("temas").child(temaId)
                .setValue(tema.copy(id = temaId), DatabaseReference.CompletionListener { error, _ ->
                    if (error != null) cont.resumeWithException(Exception(error.message))
                    else cont.resume(Unit)
                })
        }

    // Eliminar un tema
    suspend fun eliminarTema(cursoId: String, temaId: String) = 
        suspendCancellableCoroutine<Unit> { cont ->
            ref.child(cursoId).child("temas").child(temaId)
                .removeValue(DatabaseReference.CompletionListener { error, _ ->
                    if (error != null) cont.resumeWithException(Exception(error.message))
                    else cont.resume(Unit)
                })
        }

    // Agregar tema con explicación manual (aprobada automáticamente)
    suspend fun agregarTemaConExplicacion(
        cursoId: String, 
        tema: Tema, 
        explicacion: String
    ): String = suspendCancellableCoroutine { cont ->
        val refTemas = ref.child(cursoId).child("temas")
        val nuevoTemaRef = refTemas.push()
        val temaId = nuevoTemaRef.key ?: return@suspendCancellableCoroutine cont.resumeWithException(
            Exception("No se pudo generar ID del tema")
        )

        val timestamp = Instant.now().toString()
        val temaCompleto = tema.copy(
            id = temaId,
            fechaCreacion = timestamp,
            explicacion = explicacion,
            explicacionFuente = "docente",
            explicacionEstado = "aprobada",
            explicacionUltimaActualizacion = timestamp
        )

        nuevoTemaRef.setValue(temaCompleto, DatabaseReference.CompletionListener { error, _ ->
            if (error != null) cont.resumeWithException(Exception(error.message))
            else cont.resume(temaId)
        })
    }

    // Obtener todos los estudiantes de un curso (con su info completa)
    suspend fun obtenerEstudiantesPorCurso(cursoId: String): List<Map<String, Any?>> =
        suspendCancellableCoroutine { cont ->
            val refEstudiantes = database.getReference("cursos/$cursoId/estudiantes")

            refEstudiantes.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        cont.resume(emptyList())
                        return
                    }

                    val estudiantes = snapshot.children.mapNotNull { it.value as? Map<String, Any?> }
                    cont.resume(estudiantes)
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
        }
}