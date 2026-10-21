package com.eduracha.repository

import com.eduracha.utils.lecturaFirebase
import com.eduracha.utils.reanudarConError

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

    suspend fun crearQuiz(quiz: Quiz): String = lecturaFirebase { cont ->
        val nuevoRef = refQuizzes.push()
        val id = nuevoRef.key ?: return@lecturaFirebase cont.resumeWithException(
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

    suspend fun obtenerQuizPorId(quizId: String): Quiz? = lecturaFirebase { cont ->
        refQuizzes.child(quizId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    cont.resume(snapshot.getValue(Quiz::class.java))
                } catch (e: Exception) {
                    reanudarConError(cont, e, "QuizRepository.kt")
                }
            }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }

    suspend fun actualizarQuiz(quiz: Quiz) = lecturaFirebase<Unit> { cont ->
        quiz.id?.let { id ->
            refQuizzes.child(id)
                .setValue(quiz, DatabaseReference.CompletionListener { error, _ ->
                    if (error != null) cont.resumeWithException(Exception(error.message))
                    else cont.resume(Unit)
                })
        } ?: cont.resumeWithException(Exception("Quiz sin ID"))
    }

  // LOGS PARA REPORTES
    suspend fun obtenerQuizzesPorEstudiante(estudianteId: String, cursoId: String? = null): List<Quiz> =
        lecturaFirebase { cont ->
            println("[QuizRepo] Buscando quizzes:")
            println("    Estudiante ID: $estudianteId")
            println("    Curso ID: ${cursoId ?: "TODOS"}")
            
            refQuizzes.orderByChild("estudianteId").equalTo(estudianteId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        println("    Total nodos encontrados: ${snapshot.childrenCount}")
                        
                        val quizzes = mutableListOf<Quiz>()
                        var contador = 0
                        
                        for (quizSnap in snapshot.children) {
                            contador++
                            val quiz = quizSnap.getValue(Quiz::class.java)
                            
                            if (quiz != null) {
                                println("   Quiz #$contador: ${quiz.id}")
                                println("      - EstudianteId: ${quiz.estudianteId}")
                                println("      - CursoId: ${quiz.cursoId}")
                                println("      - TemaId: ${quiz.temaId}")
                                println("      - Estado: ${quiz.estado}")
                                println("      - Inicio: ${quiz.inicio}")
                                println("      - Fin: ${quiz.fin}")
                                println("      - Correctas: ${quiz.preguntasCorrectas}")
                                println("      - Total preguntas: ${quiz.preguntas.size}")
                                println("      - XP: ${quiz.experienciaGanada}")
                                
                                // Filtrar por curso si se especifica
                                if (cursoId == null || quiz.cursoId == cursoId) {
                                    quizzes.add(quiz)
                                    println("       INCLUIDO")
                                } else {
                                    println("       EXCLUIDO (curso ${quiz.cursoId} != $cursoId)")
                                }
                            } else {
                                println("    Quiz #$contador: No se pudo parsear")
                            }
                        }
                        
                        println("   Total quizzes retornados: ${quizzes.size}")
                        cont.resume(quizzes)
                } catch (e: Exception) {
                    reanudarConError(cont, e, "QuizRepository.kt")
                }
            }

                    override fun onCancelled(error: DatabaseError) {
                        println("    Error Firebase: ${error.message}")
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    //VERIFICACIONES

    suspend fun verificarTemaEnCurso(cursoId: String, temaId: String): Boolean =
        lecturaFirebase { cont ->
            refCursos.child(cursoId).child("temas").child(temaId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        cont.resume(snapshot.exists())
                } catch (e: Exception) {
                    reanudarConError(cont, e, "QuizRepository.kt")
                }
            }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    suspend fun verificarExplicacionVista(userId: String, temaId: String): Boolean =
        lecturaFirebase { cont ->
            database.getReference("explicacion_vista")
                .child(userId)
                .child(temaId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        val visto = snapshot.child("visto").getValue(Boolean::class.java) ?: false
                        cont.resume(visto)
                } catch (e: Exception) {
                    reanudarConError(cont, e, "QuizRepository.kt")
                }
            }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    /** Registro de lectura: si la vio y con qué huella (versión) de la explicación. */
    data class RegistroExplicacion(val visto: Boolean, val huella: String?)

    suspend fun obtenerRegistroExplicacion(userId: String, temaId: String): RegistroExplicacion =
        lecturaFirebase { cont ->
            database.getReference("explicacion_vista")
                .child(userId)
                .child(temaId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        try {
                            cont.resume(
                                RegistroExplicacion(
                                    visto = snapshot.child("visto").getValue(Boolean::class.java) ?: false,
                                    huella = snapshot.child("huella").getValue(String::class.java)
                                )
                            )
                        } catch (e: Exception) {
                            reanudarConError(cont, e, "QuizRepository.kt")
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    suspend fun marcarExplicacionVista(userId: String, temaId: String, huella: String? = null) =
        lecturaFirebase<Unit> { cont ->
            val data = mutableMapOf<String, Any>(
                "visto" to true,
                "timestamp" to Instant.now().toString()
            )
            if (huella != null) data["huella"] = huella

            database.getReference("explicacion_vista")
                .child(userId)
                .child(temaId)
                .setValue(data, DatabaseReference.CompletionListener { error, _ ->
                    if (error != null) cont.resumeWithException(Exception(error.message))
                    else cont.resume(Unit)
                })
        }

    suspend fun esPrimeraVezAprobado(userId: String, cursoId: String, temaId: String): Boolean =
        lecturaFirebase { cont ->
            refQuizzes.orderByChild("estudianteId").equalTo(userId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        val yaAprobo = snapshot.children.any { child ->
                            val quiz = child.getValue(Quiz::class.java)
                            quiz != null &&
                                    quiz.cursoId == cursoId &&
                                    quiz.temaId == temaId &&
                                    quiz.estado == "finalizado" &&
                                    quiz.preguntasCorrectas > 0
                        }
                        cont.resume(!yaAprobo)
                } catch (e: Exception) {
                    reanudarConError(cont, e, "QuizRepository.kt")
                }
            }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    //  GESTIÓN DE ESTADO DE TEMAS
    // apunta a usuarios/{uid}/cursos/{cursoId}/temasCompletados/{temaId}

    suspend fun obtenerEstadoTema(uid: String, cursoId: String, temaId: String): EstadoTema? = 
        lecturaFirebase { cont ->
            database.getReference("usuarios/$uid/cursos/$cursoId/temasCompletados/$temaId")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        cont.resume(snapshot.getValue(EstadoTema::class.java))
                } catch (e: Exception) {
                    reanudarConError(cont, e, "QuizRepository.kt")
                }
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
    ) = lecturaFirebase<Unit> { cont ->
        database.getReference("usuarios/$uid/cursos/$cursoId/temasCompletados/$temaId")
            .setValue(estado) { error, _ ->
                if (error != null) cont.resumeWithException(error.toException())
                else cont.resume(Unit)
            }
    }

    suspend fun obtenerPerfilCurso(uid: String, cursoId: String): PerfilCurso? =
        lecturaFirebase { cont ->
            database.getReference("usuarios/$uid/cursos/$cursoId")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
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
                            // Lectura manual: getValue() no admite Set y el nodo mezcla campos de la app y del backend
                            temasCompletados[temaId] = estadoTemaDesde(temaId, temaSnap)
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
                } catch (e: Exception) {
                    reanudarConError(cont, e, "QuizRepository.kt")
                }
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
    ) = lecturaFirebase<Unit> { cont ->
        database.getReference("usuarios/$uid/cursos/$cursoId/temasCompletados/$temaId")
            .setValue(estado) { error, _ ->
                if (error != null) cont.resumeWithException(error.toException())
                else cont.resume(Unit)
            }
    }

    /** Construye un EstadoTema leyendo los valores crudos (tolera listas o mapas en preguntasVistas). */
    fun estadoTemaDesde(temaId: String, snap: DataSnapshot): EstadoTema {
        fun entero(campo: String) = (snap.child(campo).value as? Number)?.toInt() ?: 0
        fun largo(campo: String) = (snap.child(campo).value as? Number)?.toLong() ?: 0L
        val vistas: Set<String> = when (val v = snap.child("preguntasVistas").value) {
            is List<*> -> v.mapNotNull { it?.toString() }.toSet()
            is Map<*, *> -> v.values.mapNotNull { it?.toString() }.toSet()
            else -> emptySet()
        }
        return EstadoTema(
            temaId = temaId,
            quizzesRealizados = entero("quizzesRealizados"),
            quizzesRequeridos = entero("quizzesRequeridos"),
            porcentajePromedio = entero("porcentajePromedio"),
            aprobado = snap.child("aprobado").value as? Boolean ?: false,
            fechaPrimerIntento = largo("fechaPrimerIntento"),
            fechaUltimoIntento = largo("fechaUltimoIntento"),
            preguntasVistas = vistas,
            ciclosCompletados = entero("ciclosCompletados"),
            necesitaMasPreguntas = snap.child("necesitaMasPreguntas").value as? Boolean ?: false,
            fechaSolicitudMasPreguntas = (snap.child("fechaSolicitudMasPreguntas").value as? Number)?.toLong(),
            ultimoReinicioBanco = (snap.child("ultimoReinicioBanco").value as? Number)?.toLong()
        )
    }

    /** Valores crudos del estado de un tema (o null si no existe). */
    suspend fun leerEstadoTemaCrudo(uid: String, cursoId: String, temaId: String): Map<String, Any?>? =
        lecturaFirebase { cont ->
            database.getReference("usuarios/$uid/cursos/$cursoId/temasCompletados/$temaId")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(snapshot.value as? Map<String, Any?>)
                    }
                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    /**
     * Actualiza solo los campos indicados del estado del tema (updateChildren).
     * Así no se borran los campos que escribe la app (racha, cooldown) y no se usa setValue con Set.
     */
    suspend fun actualizarCamposEstadoTema(uid: String, cursoId: String, temaId: String, campos: Map<String, Any?>) =
        lecturaFirebase<Unit> { cont ->
            database.getReference("usuarios/$uid/cursos/$cursoId/temasCompletados/$temaId")
                .updateChildren(campos) { error, _ ->
                    if (error != null) cont.resumeWithException(error.toException()) else cont.resume(Unit)
                }
        }
}
