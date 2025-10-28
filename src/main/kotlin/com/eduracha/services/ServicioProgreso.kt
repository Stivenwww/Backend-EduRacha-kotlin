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
            "ultimaFecha" to System.currentTimeMillis()
        )

        refProgreso.setValue(progresoInicial) { error, _ ->
            if (error != null) {
                println("Error al crear progreso: ${error.message}")
            } else {
                println("Progreso inicializado para $estudianteId en $cursoId")
            }
        }

        refRacha.setValue(rachaInicial) { error, _ ->
            if (error != null) {
                println("Error al crear racha: ${error.message}")
            } else {
                println("Racha inicializada para $estudianteId en $cursoId")
            }
        }

        // ✅ Notificación de bienvenida
        val refNotif = db.getReference("notificaciones").child(estudianteId).push()
        val notificacion = mapOf(
            "titulo" to "¡Bienvenido al curso!",
            "mensaje" to "Hola $nombre, tu progreso en el curso $cursoId ha sido inicializado. ¡Empieza a aprender!",
            "fecha" to System.currentTimeMillis(),
            "leido" to false
        )

        refNotif.setValue(notificacion) { error, _ ->
            if (error != null)
                println("Error al guardar notificación: ${error.message}")
            else
                println("✅ Notificación de bienvenida enviada a $nombre ($estudianteId)")
        }
    }

    // Obtener progreso (suspend)
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

    // Actualizar progreso (por quiz o práctica)
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

                    ref.setValue(nuevo) { error, _ ->
                        if (error != null) {
                            cont.resumeWithException(error.toException())
                        } else {
                            println("✅ Progreso actualizado para $usuarioId ($tipo completado)")

                            // ✅ Crear notificación automática solo para quizzes
                            if (tipo == "quiz" && completado) {
                                val refNotif = db.getReference("notificaciones").child(usuarioId).push()
                                val notif = mapOf(
                                    "titulo" to "¡Quiz completado!",
                                    "mensaje" to "Has completado un quiz y ganaste 10 puntos 🎯",
                                    "fecha" to System.currentTimeMillis(),
                                    "leido" to false
                                )
                                refNotif.setValue(notif) { e, _ ->
                                    if (e != null)
                                        println("Error al guardar notificación de quiz: ${e.message}")
                                    else
                                        println("📨 Notificación de quiz enviada a $usuarioId")
                                }
                            }

                            cont.resume(Unit)
                        }
                    }
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
            ref.setValue(Progreso()) { error, _ ->
                if (error != null) cont.resumeWithException(error.toException())
                else cont.resume(Unit)
            }
        }
}
