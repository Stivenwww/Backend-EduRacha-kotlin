package com.eduracha.repository

import com.eduracha.models.*
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class QuizRepository {

    private val database = FirebaseDatabase.getInstance()
    private val refQuizzes = database.getReference("quizzes")
    private val refCursos = database.getReference("cursos")

    //GESTIÓN DE QUIZZES 

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

    suspend fun actualizarQuiz(quiz: Quiz) = suspendCancellableCoroutine<Unit> { cont ->
        quiz.id?.let { id ->
            refQuizzes.child(id)
                .setValue(quiz, DatabaseReference.CompletionListener { error, _ ->
                    if (error != null) cont.resumeWithException(Exception(error.message))
                    else cont.resume(Unit)
                })
        } ?: cont.resumeWithException(Exception("Quiz sin ID"))
    }

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

    //VERIFICACIONES

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

    //  GESTIÓN DE ESTADO DE TEMAS
    // apunta a usuarios/{uid}/cursos/{cursoId}/temasCompletados/{temaId}

    suspend fun obtenerEstadoTema(uid: String, cursoId: String, temaId: String): EstadoTema? = 
        suspendCancellableCoroutine { cont ->
            database.getReference("usuarios/$uid/cursos/$cursoId/temasCompletados/$temaId")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        cont.resume(snapshot.getValue(EstadoTema::class.java))
                    }
                    
                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    suspend fun actualizarEstadoTema(
        uid: String, 
        cursoId: String, 
        temaId: String, 
        estado: EstadoTema
    ) = suspendCancellableCoroutine<Unit> { cont ->
        database.getReference("usuarios/$uid/cursos/$cursoId/temasCompletados/$temaId")
            .setValue(estado) { error, _ ->
                if (error != null) cont.resumeWithException(error.toException())
                else cont.resume(Unit)
            }
    }

    suspend fun obtenerPerfilCurso(uid: String, cursoId: String): PerfilCurso? =
        suspendCancellableCoroutine { cont ->
            database.getReference("usuarios/$uid/cursos/$cursoId")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        // Construir PerfilCurso desde la estructura plana
                        val progreso = snapshot.child("progreso").value as? Map<String, Any?>
                        val temasCompletadosSnapshot = snapshot.child("temasCompletados")
                        
                        if (progreso == null) {
                            cont.resume(null)
                            return
                        }

                        val temasCompletados = mutableMapOf<String, EstadoTema>()
                        temasCompletadosSnapshot.children.forEach { temaSnap ->
                            val temaId = temaSnap.key ?: return@forEach
                            val estadoTema = temaSnap.getValue(EstadoTema::class.java) ?: return@forEach
                            temasCompletados[temaId] = estadoTema
                        }

                        val perfilCurso = PerfilCurso(
                            cursoId = progreso["cursoId"] as? String ?: "",
                            vidas = Vidas(
                                actuales = (progreso["vidas"] as? Number)?.toInt() ?: 5,
                                max = (progreso["vidasMax"] as? Number)?.toInt() ?: 5,
                                ultimaRegen = (progreso["ultimaRegen"] as? Number)?.toLong() ?: 0
                            ),
                            racha = Racha(
                                diasConsecutivos = (progreso["diasConsecutivos"] as? Number)?.toInt() ?: 0,
                                ultimaFecha = (progreso["ultimaFecha"] as? Number)?.toLong() ?: 0,
                                mejorRacha = (progreso["mejorRacha"] as? Number)?.toInt() ?: 0
                            ),
                            progreso = Progreso(
                                porcentaje = (progreso["porcentaje"] as? Number)?.toInt() ?: 0,
                                temasCompletados = (progreso["temasCompletados"] as? Number)?.toInt() ?: 0,
                                quizzesCompletados = (progreso["quizzesCompletados"] as? Number)?.toInt() ?: 0,
                                practicasCompletadas = (progreso["practicasCompletadas"] as? Number)?.toInt() ?: 0,
                                ultimaActividad = (progreso["ultimaActividad"] as? Number)?.toLong() ?: 0
                            ),
                            experiencia = (progreso["experiencia"] as? Number)?.toInt() ?: 0,
                            temasCompletados = temasCompletados
                        )

                        cont.resume(perfilCurso)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    suspend fun crearOActualizarEstadoTema(
        uid: String,
        cursoId: String,
        temaId: String,
        estado: EstadoTema
    ) = suspendCancellableCoroutine<Unit> { cont ->
        database.getReference("usuarios/$uid/cursos/$cursoId/temasCompletados/$temaId")
            .setValue(estado) { error, _ ->
                if (error != null) cont.resumeWithException(error.toException())
                else cont.resume(Unit)
            }
    }
}