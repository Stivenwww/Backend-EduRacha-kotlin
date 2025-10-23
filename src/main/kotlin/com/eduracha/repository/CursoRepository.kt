package com.eduracha.repository

import com.eduracha.models.Curso
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
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
                