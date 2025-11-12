package com.eduracha.services

import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


object ServicioProgreso {

    private val db = FirebaseDatabase.getInstance()

    
     //Inicializar progreso del estudiante al inscribirse
     // Crea: usuarios/{uid}/cursos/{cursoId}/progreso
    
    fun inicializarProgresoEstudiante(
        estudianteId: String, 
        cursoId: String, 
        nombre: String, 
        email: String
    ) {
        val refProgreso = db.getReference("usuarios/$estudianteId/cursos/$cursoId/progreso")

        val progresoInicial = mapOf(
            "estudianteId" to estudianteId,
            "cursoId" to cursoId,
            "nombre" to nombre,
            "email" to email,
            "quizzesCompletados" to 0,
            "practicasCompletadas" to 0,
            "porcentaje" to 0,
            "temasCompletados" to 0,
            "experiencia" to 0,
            "vidas" to 5,
            "vidasMax" to 5,
            "diasConsecutivos" to 0,
            "ultimaFecha" to System.currentTimeMillis(),
            "ultimaActividad" to System.currentTimeMillis(),
            "ultimaRegen" to System.currentTimeMillis()
        )

        refProgreso.setValue(progresoInicial, object : DatabaseReference.CompletionListener {
            override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                if (error != null) {
                    println(" Error al inicializar progreso: ${error.message}")
                } else {
                    println(" Progreso inicializado para $nombre en curso $cursoId")
                    enviarNotificacionBienvenida(estudianteId, nombre, cursoId)
                }
            }
        })
    }

    
     // Obtener progreso completo del estudiante
     
    suspend fun obtenerProgreso(usuarioId: String, cursoId: String): Map<String, Any>? =
        suspendCancellableCoroutine { cont ->
            val ref = db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
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

 // Actualizar progreso después de quiz 
    suspend fun actualizarProgresoQuiz(
        usuarioId: String,
        cursoId: String,
        xpGanado: Int,
        vidasPerdidas: Int,
        aprobado: Boolean
    ) = suspendCancellableCoroutine { cont ->
        val ref = db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
        
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val actual = snapshot.value as? Map<String, Any> ?: mapOf()
                
                // Valores actuales
                val xpActual = (actual["experiencia"] as? Number)?.toInt() ?: 0
                val vidasActuales = (actual["vidas"] as? Number)?.toInt() ?: 5
                val vidasMax = (actual["vidasMax"] as? Number)?.toInt() ?: 5
                val quizzesCompletados = (actual["quizzesCompletados"] as? Number)?.toInt() ?: 0
                
                // Cálculo de racha
                val ahora = System.currentTimeMillis()
                val ultimaFecha = (actual["ultimaFecha"] as? Number)?.toLong() ?: 0
                val diasConsecutivos = (actual["diasConsecutivos"] as? Number)?.toInt() ?: 0
                
                val diferenciaDias = (ahora - ultimaFecha) / (1000 * 60 * 60 * 24)
                val nuevaRacha = when {
                    diferenciaDias >= 2L -> if (aprobado) 1 else 0 // Racha perdida
                    diferenciaDias >= 1L -> if (aprobado) diasConsecutivos + 1 else diasConsecutivos
                    else -> diasConsecutivos // Mismo día
                }
                
                // Calcular nuevas vidas (no puede bajar de 0 ni superar el máximo)
                val nuevasVidas = (vidasActuales - vidasPerdidas).coerceIn(0, vidasMax)
                
                // Actualización
                val actualizacion = mapOf(
                    "experiencia" to (xpActual + xpGanado),
                    "vidas" to nuevasVidas,
                    "quizzesCompletados" to (quizzesCompletados + 1),
                    "diasConsecutivos" to nuevaRacha,
                    "ultimaFecha" to ahora,
                    "ultimaActividad" to ahora,
                    "ultimaRegen" to ahora
                )

                ref.updateChildren(actualizacion, object : DatabaseReference.CompletionListener {
                    override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                        if (error != null) {
                            cont.resumeWithException(error.toException())
                        } else {
                            println("Progreso actualizado: +$xpGanado XP, ${nuevasVidas} vidas, racha ${nuevaRacha} días")
                            
                            if (aprobado) {
                                enviarNotificacionQuizCompletado(usuarioId, xpGanado, nuevaRacha)
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

    
     // Actualizar progreso después de práctica
    
    suspend fun actualizarProgresoPractica(
        usuarioId: String,
        cursoId: String,
        completado: Boolean
    ) = suspendCancellableCoroutine { cont ->
        val ref = db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
        
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val actual = snapshot.value as? Map<String, Any> ?: mapOf()
                
                val xpActual = (actual["experiencia"] as? Number)?.toInt() ?: 0
                val practicasCompletadas = (actual["practicasCompletadas"] as? Number)?.toInt() ?: 0
                
                val xpPractica = if (completado) 5 else 0
                
                val actualizacion = mapOf(
                    "experiencia" to (xpActual + xpPractica),
                    "practicasCompletadas" to (practicasCompletadas + if (completado) 1 else 0),
                    "ultimaActividad" to System.currentTimeMillis()
                )

                ref.updateChildren(actualizacion, object : DatabaseReference.CompletionListener {
                    override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                        if (error != null) {
                            cont.resumeWithException(error.toException())
                        } else {
                            println(" Práctica actualizada: +$xpPractica XP")
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

    // Regenerar vidas automáticamente
    suspend fun regenerarVidas(
        usuarioId: String,
        cursoId: String,
        vidaRegenMinutos: Int = 30
    ) = suspendCancellableCoroutine { cont ->
        val ref = db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
        
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val actual = snapshot.value as? Map<String, Any> ?: mapOf()
                
                val vidasActuales = (actual["vidas"] as? Number)?.toInt() ?: 5
                val vidasMax = (actual["vidasMax"] as? Number)?.toInt() ?: 5
                val ultimaRegen = (actual["ultimaRegen"] as? Number)?.toLong() ?: System.currentTimeMillis()
                
                val ahora = System.currentTimeMillis()
                val minutosPasados = (ahora - ultimaRegen) / (1000 * 60)
                val vidasRecuperadas = (minutosPasados / vidaRegenMinutos).toInt()
                
                if (vidasRecuperadas > 0 && vidasActuales < vidasMax) {
                    val nuevasVidas = (vidasActuales + vidasRecuperadas).coerceAtMost(vidasMax)
                    
                    val actualizacion = mapOf(
                        "vidas" to nuevasVidas,
                        "ultimaRegen" to ahora
                    )
                    
                    ref.updateChildren(actualizacion, object : DatabaseReference.CompletionListener {
                        override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                            if (error != null) cont.resumeWithException(error.toException())
                            else {
                                println("✅ Vidas regeneradas: $vidasActuales -> $nuevasVidas")
                                cont.resume(Unit)
                            }
                        }
                    })
                } else {
                    cont.resume(Unit)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }

     // Obtener racha y estadísticas
    suspend fun obtenerRacha(cursoId: String, estudianteId: String): Map<String, Any>? =
        obtenerProgreso(estudianteId, cursoId)

    suspend fun reiniciarProgreso(usuarioId: String, cursoId: String) =
        suspendCancellableCoroutine { cont ->
            val ref = db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
            
            val progresoInicial = mapOf(
                "quizzesCompletados" to 0,
                "practicasCompletadas" to 0,
                "porcentaje" to 0,
                "temasCompletados" to 0,
                "experiencia" to 0,
                "vidas" to 5,
                "vidasMax" to 5,
                "diasConsecutivos" to 0,
                "ultimaFecha" to System.currentTimeMillis(),
                "ultimaActividad" to System.currentTimeMillis(),
                "ultimaRegen" to System.currentTimeMillis()
            )
            
            ref.setValue(progresoInicial, object : DatabaseReference.CompletionListener {
                override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                    if (error != null) cont.resumeWithException(error.toException())
                    else cont.resume(Unit)
                }
            })
        }

    private fun enviarNotificacionBienvenida(
        estudianteId: String,
        nombre: String,
        cursoId: String
    ) {
        val refNotif = db.getReference("notificaciones").child(estudianteId).push()
        val notificacion = mapOf(
            "titulo" to "¡Bienvenido al curso!",
            "mensaje" to "Hola $nombre, estás inscrito en el curso. ¡Empieza a aprender!",
            "fecha" to System.currentTimeMillis(),
            "leido" to false,
            "tipo" to "bienvenida",
            "cursoId" to cursoId
        )

        refNotif.setValue(notificacion, object : DatabaseReference.CompletionListener {
            override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                if (error != null)
                    println(" Error al enviar notificación: ${error.message}")
                else
                    println(" Notificación de bienvenida enviada a $nombre")
            }
        })
    }

    private fun enviarNotificacionQuizCompletado(
        estudianteId: String,
        xpGanado: Int,
        racha: Int
    ) {
        val refNotif = db.getReference("notificaciones").child(estudianteId).push()
        val mensaje = if (racha > 1) {
            "¡Quiz completado! Ganaste $xpGanado XP.  Racha de $racha días"
        } else {
            "¡Quiz completado! Ganaste $xpGanado XP."
        }
        
        val notificacion = mapOf(
            "titulo" to "¡Quiz completado!",
            "mensaje" to mensaje,
            "fecha" to System.currentTimeMillis(),
            "leido" to false,
            "tipo" to "quiz_completado"
        )

        refNotif.setValue(notificacion, object : DatabaseReference.CompletionListener {
            override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                if (error != null)
                    println(" Error al enviar notificación: ${error.message}")
                else
                    println("Notificación de quiz enviada")
            }
        })
    }
}