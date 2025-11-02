package com.eduracha.repository

import com.eduracha.models.*
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

class QuizRepository {

    private val database = FirebaseDatabase.getInstance()
    private val refQuizzes = database.getReference("quizzes")
    private val refInscripciones = database.getReference("inscripciones")
    private val refCursos = database.getReference("cursos")

    // Crear un nuevo quiz
    suspend fun crearQuiz(quiz: Quiz): String = suspendCancellableCoroutine { cont ->
        val nuevoRef = refQuizzes.push()
        val id = nuevoRef.key ?: return@suspendCancellableCoroutine cont.resumeWithException(
            Exception("No se pudo generar ID del quiz")
        )

        val nuevoQuiz = quiz.copy(
            id = id,
            inicio = Instant.now().toString(),
            estado = "en_progreso"
        )

        nuevoRef.setValue(nuevoQuiz, DatabaseReference.CompletionListener { error, _ ->
            if (error != null) cont.resumeWithException(Exception(error.message))
            else cont.resume(id)
        })
    }

    // Obtener un quiz por ID
    suspend fun obtenerQuizPorId(quizId: String): Quiz? = suspendCancellableCoroutine { cont ->
        refQuizzes.child(quizId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    cont.resume(snapshot.getValue(Quiz::class.java))
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }

    // Actualizar quiz completo
    suspend fun actualizarQuiz(quiz: Quiz) = suspendCancellableCoroutine<Unit> { cont ->
        quiz.id?.let { id ->
            refQuizzes.child(id)
                .setValue(quiz, DatabaseReference.CompletionListener { error, _ ->
                    if (error != null) cont.resumeWithException(Exception(error.message))
                    else cont.resume(Unit)
                })
        } ?: cont.resumeWithException(Exception("Quiz sin ID"))
    }

    // Obtener inscripción del estudiante
    suspend fun obtenerInscripcion(cursoId: String, userId: String): Inscripcion? =
        suspendCancellableCoroutine { cont ->
            refInscripciones.child(cursoId).child(userId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        cont.resume(snapshot.getValue(Inscripcion::class.java))
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    // Actualizar inscripción (vidas, intentos, etc.)
    suspend fun actualizarInscripcion(cursoId: String, inscripcion: Inscripcion) =
        suspendCancellableCoroutine<Unit> { cont ->
            refInscripciones.child(cursoId).child(inscripcion.userId)
                .setValue(inscripcion, DatabaseReference.CompletionListener { error, _ ->
                    if (error != null) cont.resumeWithException(Exception(error.message))
                    else cont.resume(Unit)
                })
        }

    // Verificar si el tema pertenece al curso
    suspend fun verificarTemaEnCurso(cursoId: String, temaId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            refCursos.child(cursoId).child("temas").child(temaId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        cont.resume(snapshot.exists())
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    // Verificar si vio la explicación
    suspend fun verificarExplicacionVista(userId: String, temaId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            database.getReference("explicacion_vista")
                .child(userId)
                .child(temaId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val visto = snapshot.child("visto").getValue(Boolean::class.java) ?: false
                        cont.resume(visto)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    // Marcar explicación como vista
    suspend fun marcarExplicacionVista(userId: String, temaId: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            val data = mapOf(
                "visto" to true,
                "timestamp" to Instant.now().toString()
            )

            database.getReference("explicacion_vista")
                .child(userId)
                .child(temaId)
                .setValue(data, DatabaseReference.CompletionListener { error, _ ->
                    if (error != null) cont.resumeWithException(Exception(error.message))
                    else cont.resume(Unit)
                })
        }

    // Verificar si es la primera vez que aprueba el tema
    suspend fun esPrimeraVezAprobado(userId: String, cursoId: String, temaId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            refQuizzes.orderByChild("estudianteId").equalTo(userId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val yaAprobo = snapshot.children.any { child ->
                            val quiz = child.getValue(Quiz::class.java)
                            quiz != null &&
                                    quiz.cursoId == cursoId &&
                                    quiz.temaId == temaId &&
                                    quiz.estado == "finalizado" &&
                                    quiz.preguntasCorrectas > 0
                        }
                        cont.resume(!yaAprobo)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    // Regenerar vidas automáticamente
    suspend fun regenerarVidas(cursoId: String, inscripcion: Inscripcion, vidaRegenMinutos: Int): Inscripcion {
        val ahora = System.currentTimeMillis()
        val minutosPasados = (ahora - inscripcion.ultimaRegen) / (1000 * 60)
        val vidasRecuperadas = (minutosPasados / vidaRegenMinutos).toInt()

        return if (vidasRecuperadas > 0) {
            inscripcion.copy(
                vidasActuales = min(inscripcion.vidasMax, inscripcion.vidasActuales + vidasRecuperadas),
                ultimaRegen = ahora
            )
        } else {
            inscripcion
        }
    }

    // Obtener quizzes por estudiante
    suspend fun obtenerQuizzesPorEstudiante(estudianteId: String, cursoId: String? = null): List<Quiz> =
        suspendCancellableCoroutine { cont ->
            refQuizzes.orderByChild("estudianteId").equalTo(estudianteId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val quizzes = snapshot.children.mapNotNull {
                            it.getValue(Quiz::class.java)
                        }.filter { cursoId == null || it.cursoId == cursoId }

                        cont.resume(quizzes)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }
}