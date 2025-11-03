package com.eduracha.services

import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Progreso(
    val quizzesCompletados: Int = 0,
    val practicasCompletadas: Int = 0,
    val puntosTotales: Int = 0
)

object ServicioProgreso {

    private val db = FirebaseDatabase.getInstance()

    // Inicializar datos de estudiante al ingresar al curso
    fun inicializarDatosEstudiante(estudianteId: String, cursoId: String, nombre: String, email: String) {
        val refProgreso = db.getReference("progresos").child(cursoId).child(estudianteId)
        val refRacha = db.getReference("rachas").child(cursoId).child(estudianteId)

        val progresoInicial = mapOf(
            "porcentaje" to 0,
            "temasCompletados" to 0,
            "ultimaActividad" to System.currentTimeMillis()
        )

        val rachaInicial = mapOf(
            "diasConsecutivos" to 0,
            "ultimaFecha" to System.currentTimeMillis(),
            "vidas" to 5,
            "experiencia" to 0
        )

        // Guardar progreso inicial
        refProgreso.setValue(progresoInicial, object : DatabaseReference.CompletionListener {
            override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                if (error != null) {
                    println("Error al crear progreso: ${error.message}")
                } else {
                    println("Progreso inicializado para $estudianteId en $cursoId")
                }
            }
        })

        // Guardar racha inicial
        refRacha.setValue(rachaInicial, object : DatabaseReference.CompletionListener {
            override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                if (error != null) {
                    println("Error al crear racha: ${error.message}")
                } else {
                    println("Racha inicializada para $estudianteId en $cursoId")
                }
            }
        })

        // Notificación de bienvenida
        val refNotif = db.getReference("notificaciones").child(estudianteId).push()
        val notificacion = mapOf(
            "titulo" to "¡Bienvenido al curso!",
            "mensaje" to "Hola $nombre, tu progreso en el curso $cursoId ha sido inicializado. ¡Empieza a aprender!",
            "fecha" to System.currentTimeMillis(),
            "leido" to false
        )

        refNotif.setValue(notificacion, object : DatabaseReference.CompletionListener {
            override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                if (error != null)
                    println("Error al guardar notificación: ${error.message}")
                else
                    println("Notificación de bienvenida enviada a $nombre ($estudianteId)")
            }
        })
    }

    // Obtener progreso
    suspend fun obtenerProgreso(usuarioId: String): Progreso? =
        suspendCancellableCoroutine { cont ->
            val ref = db.getReference("progreso").child(usuarioId)
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val progreso = snapshot.getValue(Progreso::class.java)
                    cont.resume(progreso)
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
        }

    // Actualizar progreso
    suspend fun actualizarProgreso(usuarioId: String, tipo: String, completado: Boolean) =
        suspendCancellableCoroutine { cont ->
            val ref = db.getReference("progreso").child(usuarioId)
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val actual = snapshot.getValue(Progreso::class.java) ?: Progreso()
                    val nuevo = when (tipo) {
                        "quiz" -> actual.copy(
                            quizzesCompletados = actual.quizzesCompletados + if (completado) 1 else 0,
                            puntosTotales = actual.puntosTotales + if (completado) 10 else 0
                        )
                        "practica" -> actual.copy(
                            practicasCompletadas = actual.practicasCompletadas + if (completado) 1 else 0,
                            puntosTotales = actual.puntosTotales + if (completado) 5 else 0
                        )
                        else -> actual
                    }

                    ref.setValue(nuevo, object : DatabaseReference.CompletionListener {
                        override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                            if (error != null) {
                                cont.resumeWithException(error.toException())
                            } else {
                                println("Progreso actualizado para $usuarioId ($tipo completado)")

                                if (tipo == "quiz" && completado) {
                                    val refNotif = db.getReference("notificaciones").child(usuarioId).push()
                                    val notif = mapOf(
                                        "titulo" to "¡Quiz completado!",
                                        "mensaje" to "Has completado un quiz y ganaste 10 puntos.",
                                        "fecha" to System.currentTimeMillis(),
                                        "leido" to false
                                    )
                                    
                              // Guardar notificación de quiz completado
                                    refNotif.setValue(notif, object : DatabaseReference.CompletionListener {
                                        override fun onComplete(errorNotif: DatabaseError?, refNotif: DatabaseReference) {
                                            if (errorNotif != null)
                                                println("Error al enviar notificación: ${errorNotif.message}")
                                            else
                                                println("Notificación de quiz enviada a $usuarioId")
                                        }
                                    })
                                }

                                cont.resume(Unit)
                            }
                        }
                    })
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
        }

    // Reiniciar progreso
    suspend fun reiniciarProgreso(usuarioId: String) =
        suspendCancellableCoroutine { cont ->
            val ref = db.getReference("progreso").child(usuarioId)
            ref.setValue(Progreso(), object : DatabaseReference.CompletionListener {
                override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                    if (error != null) cont.resumeWithException(error.toException())
                    else cont.resume(Unit)
                }
            })
        }

    // Obtener racha
    suspend fun obtenerRacha(cursoId: String, estudianteId: String): Map<String, Any>? =
        suspendCancellableCoroutine { cont ->
            val ref = db.getReference("rachas").child(cursoId).child(estudianteId)
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val data = snapshot.value as? Map<String, Any>
                    cont.resume(data)
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
        }

    // Actualizar racha (maneja experiencia y vidas)
    suspend fun actualizarRacha(cursoId: String, estudianteId: String, exito: Boolean = true) =
        suspendCancellableCoroutine { cont ->
            val ref = db.getReference("rachas").child(cursoId).child(estudianteId)
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val hoy = System.currentTimeMillis()
                    val actual = snapshot.value as? Map<String, Any>

                    val ultimaFecha = (actual?.get("ultimaFecha") as? Number)?.toLong() ?: 0
                    val diasConsecutivos = (actual?.get("diasConsecutivos") as? Number)?.toInt() ?: 0
                    val vidas = (actual?.get("vidas") as? Number)?.toInt() ?: 5
                    val experiencia = (actual?.get("experiencia") as? Number)?.toInt() ?: 0

                    val diferenciaDias = (hoy - ultimaFecha) / (1000 * 60 * 60 * 24)

                    var nuevoConteo = diasConsecutivos
                    var nuevasVidas = vidas
                    var nuevaExperiencia = experiencia

                    if (diferenciaDias >= 1L) {
                        nuevoConteo = if (diferenciaDias == 1L) diasConsecutivos + 1 else 1
                    }

                    if (exito) {
                        nuevaExperiencia += 20
                        nuevasVidas = (vidas + 1).coerceAtMost(5)
                    } else {
                        nuevasVidas = (vidas - 1).coerceAtLeast(0)
                        nuevaExperiencia = (experiencia - 5).coerceAtLeast(0)
                    }

                    val nuevaRacha = mapOf(
                        "diasConsecutivos" to nuevoConteo,
                        "ultimaFecha" to hoy,
                        "vidas" to nuevasVidas,
                        "experiencia" to nuevaExperiencia
                    )

                    ref.setValue(nuevaRacha, object : DatabaseReference.CompletionListener {
                        override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                            if (error != null) {
                                cont.resumeWithException(error.toException())
                            } else {
                                println(
                                    "Racha actualizada para $estudianteId en $cursoId: $nuevoConteo días, $nuevasVidas vidas, $nuevaExperiencia XP"
                                )
                                cont.resume(Unit)
                            }
                        }
                    })
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
        }
}