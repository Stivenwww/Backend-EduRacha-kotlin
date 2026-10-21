package com.eduracha.services

import com.eduracha.utils.lecturaFirebase
import com.eduracha.utils.reanudarConError

import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.TimeUnit
import java.util.Calendar

object ServicioProgreso {

    private val db = FirebaseDatabase.getInstance()

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
            "mejorRacha" to 0,
            "ultimaFecha" to 0L,
            "ultimaActividad" to System.currentTimeMillis(),
            "ultimaRegen" to System.currentTimeMillis()
        )

        refProgreso.setValue(progresoInicial) { error, _ ->
            if (error != null) {
                println("Error al inicializar progreso: ${error.message}")
            } else {
                println("Progreso inicializado para $nombre en curso $cursoId")
                enviarNotificacionBienvenida(estudianteId, nombre, cursoId)
            }
        }
    }

    suspend fun obtenerProgreso(usuarioId: String, cursoId: String): Map<String, Any>? =
        lecturaFirebase { cont ->
            val ref = db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val data = snapshot.value as? Map<String, Any>
                    cont.resume(data)
                } catch (e: Exception) {
                    reanudarConError(cont, e, "ServicioProgreso.kt")
                }
            }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
        }

    /**
     * Igual que [obtenerProgreso], pero si el estudiante figura en cursos/{cursoId}/estudiantes
     * y le falta el nodo de progreso (inscripciones antiguas o incompletas), lo crea y lo devuelve.
     * Devuelve null solo si de verdad no está inscrito.
     */
    suspend fun obtenerOReconstruirProgreso(usuarioId: String, cursoId: String): Map<String, Any>? {
        obtenerProgreso(usuarioId, cursoId)?.let { return it }

        val estudiante: Map<String, Any?>? = lecturaFirebase { cont ->
            db.getReference("cursos/$cursoId/estudiantes/$usuarioId")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(if (snapshot.exists()) (snapshot.value as? Map<String, Any?> ?: emptyMap()) else null)
                    }
                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }
        // Inscripciones antiguas guardadas con otra clave y el UID en un campo (id / userId / uid)
        val estudianteFinal = estudiante ?: lecturaFirebase<Map<String, Any?>?> { cont ->
            db.getReference("cursos/$cursoId/estudiantes")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val hallado = snapshot.children.firstOrNull { c ->
                            listOf("id", "userId", "uid", "estudianteId").any { k -> c.child(k).value == usuarioId }
                        }
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(hallado?.value as? Map<String, Any?>)
                    }
                    override fun onCancelled(error: DatabaseError) { cont.resume(null) }
                })
        }
        if (estudianteFinal == null) {
            println("Progreso: $usuarioId no figura en cursos/$cursoId/estudiantes")
            return null
        }
        if ((estudianteFinal["estado"] as? String)?.lowercase() in setOf("eliminado", "inactivo", "bloqueado")) {
            return null
        }

        val ahora = System.currentTimeMillis()
        val progresoInicial: Map<String, Any> = mapOf(
            "estudianteId" to usuarioId,
            "cursoId" to cursoId,
            "nombre" to (estudianteFinal["nombre"] as? String ?: ""),
            "email" to (estudianteFinal["email"] as? String ?: ""),
            "quizzesCompletados" to 0,
            "practicasCompletadas" to 0,
            "porcentaje" to 0,
            "temasCompletados" to 0,
            "experiencia" to 0,
            "vidas" to 5,
            "vidasMax" to 5,
            "diasConsecutivos" to 0,
            "mejorRacha" to 0,
            "ultimaFecha" to 0L,
            "ultimaActividad" to ahora,
            "ultimaRegen" to ahora
        )
        lecturaFirebase<Unit> { cont ->
            db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
                .setValue(progresoInicial) { error, _ ->
                    if (error != null) cont.resumeWithException(error.toException()) else cont.resume(Unit)
                }
        }
        println("Progreso reconstruido para $usuarioId en curso $cursoId (estaba inscrito sin progreso)")
        return progresoInicial
    }

    // Actualizar progreso tras completar un quiz
    suspend fun actualizarProgresoQuiz(
        usuarioId: String,
        cursoId: String,
        xpGanado: Int,
        vidasPerdidas: Int, 
        aprobado: Boolean,
        actualizarRacha: Boolean = true,
        esPractica: Boolean = false 
    ) = lecturaFirebase { cont ->
        val ref = db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
        
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                val actual = snapshot.value as? Map<String, Any> ?: mapOf()
                
                val xpActual = (actual["experiencia"] as? Number)?.toInt() ?: 0
                val quizzesCompletados = (actual["quizzesCompletados"] as? Number)?.toInt() ?: 0
                val practicasCompletadas = (actual["practicasCompletadas"] as? Number)?.toInt() ?: 0
                
                val ahora = System.currentTimeMillis()
                
                //  Calcular racha (solo si es quiz oficial aprobado)
                val nuevaRacha: Int
                val mejorRacha: Int
                
                if (actualizarRacha && aprobado) {
                    val ultimaFecha = (actual["ultimaFecha"] as? Number)?.toLong() ?: 0L
                    val diasConsecutivos = (actual["diasConsecutivos"] as? Number)?.toInt() ?: 0
                    val mejorRachaActual = (actual["mejorRacha"] as? Number)?.toInt() ?: 0
                    
                    // Usar Calendar para comparar días correctamente
                    val cal1 = Calendar.getInstance().apply {
                        timeInMillis = ultimaFecha
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    
                    val cal2 = Calendar.getInstance().apply {
                        timeInMillis = ahora
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    
                    val diferenciaDias = TimeUnit.MILLISECONDS.toDays(
                        cal2.timeInMillis - cal1.timeInMillis
                    )
                    
                    println(" Calculando racha: ultimaFecha=$ultimaFecha, ahora=$ahora, diferencia=${diferenciaDias}d, rachaActual=$diasConsecutivos")
                    
                    nuevaRacha = when {
                        ultimaFecha == 0L -> {
                            println(" Primera vez - Racha = 1")
                            1
                        }
                        diferenciaDias == 0L -> {
                            println(" Mismo día - Racha = $diasConsecutivos")
                            diasConsecutivos
                        }
                        diferenciaDias == 1L -> {
                            println(" Día consecutivo - Racha = ${diasConsecutivos + 1}")
                            diasConsecutivos + 1
                        }
                        else -> {
                            println("Se rompió la racha - Reiniciando a 1")
                            1
                        }
                    }
                    
                    mejorRacha = maxOf(mejorRachaActual, nuevaRacha)
                    println(" Nueva racha: $nuevaRacha, Mejor racha: $mejorRacha")
                } else {
                    nuevaRacha = (actual["diasConsecutivos"] as? Number)?.toInt() ?: 0
                    mejorRacha = (actual["mejorRacha"] as? Number)?.toInt() ?: 0
                    println("ℹNo se actualiza racha (practica=${!actualizarRacha}, aprobado=$aprobado)")
                }
                
                val actualizacion = mutableMapOf<String, Any>(
                    "experiencia" to (xpActual + xpGanado),
                    "ultimaActividad" to ahora
                )
                
                // Incrementar quizzesCompletados (tanto oficial como práctica)
                actualizacion["quizzesCompletados"] = quizzesCompletados + 1
                println(" Quiz completado: total=${quizzesCompletados + 1} (oficial + práctica)")
                
                // Actualizar racha SOLO si es quiz oficial aprobado
                if (actualizarRacha && aprobado) {
                    actualizacion["diasConsecutivos"] = nuevaRacha
                    actualizacion["mejorRacha"] = mejorRacha
                    actualizacion["ultimaFecha"] = ahora
                    println("Actualizando fecha de racha: $ahora")
                }

                ref.updateChildren(actualizacion) { error, _ ->
                    if (error != null) {
                        println(" Error actualizando progreso: ${error.message}")
                        cont.resumeWithException(error.toException())
                    } else {
                        val tipo = if (actualizarRacha) "Quiz oficial" else "Práctica"
                        println("$tipo actualizado: +$xpGanado XP, racha $nuevaRacha días")
                        
                        if (aprobado && actualizarRacha) {
                            enviarNotificacionQuizCompletado(usuarioId, xpGanado, nuevaRacha)
                        }
                        
                        cont.resume(Unit)
                    }
                }
                } catch (e: Exception) {
                    reanudarConError(cont, e, "ServicioProgreso.kt")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }
    
    // Actualizar porcentaje del curso cuando se completa un tema
    suspend fun actualizarPorcentajeCurso(
        usuarioId: String,
        cursoId: String,
        totalTemas: Int
    ) = lecturaFirebase<Unit> { cont ->
        val refProgreso = db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
        val refTemas = db.getReference("usuarios/$usuarioId/cursos/$cursoId/temasCompletados")
        
        // Contar temas aprobados
        refTemas.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                var temasAprobados = 0
                
                snapshot.children.forEach { temaSnap ->
                    val aprobado = temaSnap.child("aprobado").getValue(Boolean::class.java) ?: false
                    if (aprobado) temasAprobados++
                }
                
                val porcentaje = if (totalTemas > 0) {
                    (temasAprobados * 100) / totalTemas
                } else 0
                
                val actualizacion = mapOf(
                    "temasCompletados" to temasAprobados,
                    "porcentaje" to porcentaje
                )
                
                refProgreso.updateChildren(actualizacion) { error, _ ->
                    if (error != null) {
                        println(" Error actualizando porcentaje: ${error.message}")
                        cont.resumeWithException(error.toException())
                    } else {
                        println("Porcentaje actualizado: $porcentaje% ($temasAprobados/$totalTemas temas)")
                        cont.resume(Unit)
                    }
                }
                } catch (e: Exception) {
                    reanudarConError(cont, e, "ServicioProgreso.kt")
                }
            }
            
            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }

    // Regeneración automática de vidas
    suspend fun regenerarVidas(
        usuarioId: String,
        cursoId: String,
        vidaRegenMinutos: Int = 30
    ) = lecturaFirebase { cont ->
        val ref = db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
        
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
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
                    
                    ref.updateChildren(actualizacion) { error, _ ->
                        if (error != null) cont.resumeWithException(error.toException())
                        else {
                            println("Vidas regeneradas: $vidasActuales -> $nuevasVidas")
                            cont.resume(Unit)
                        }
                    }
                } else {
                    cont.resume(Unit)
                }
                } catch (e: Exception) {
                    reanudarConError(cont, e, "ServicioProgreso.kt")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }

    suspend fun obtenerRacha(cursoId: String, estudianteId: String): Map<String, Any>? =
        obtenerProgreso(estudianteId, cursoId)

    // Actualizar vidas en tiempo real durante el quiz
    suspend fun actualizarVidasInmediato(
        usuarioId: String,
        cursoId: String,
        nuevasVidas: Int
    ) = lecturaFirebase<Unit> { cont ->
        val ref = db.getReference("usuarios/$usuarioId/cursos/$cursoId/progreso")
        
        val vidasCoerced = nuevasVidas.coerceIn(0, 5)
        
        val actualizacion = mapOf(
            "vidas" to vidasCoerced,
            "ultimaRegen" to System.currentTimeMillis()
        )
        
        ref.updateChildren(actualizacion) { error, _ ->
            if (error != null) {
                println(" Error actualizando vidas inmediato: ${error.message}")
                cont.resumeWithException(error.toException())
            } else {
                println(" Vidas actualizadas en tiempo real: $vidasCoerced")
                cont.resume(Unit)
            }
        }
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

        refNotif.setValue(notificacion) { error, _ ->
            if (error != null)
                println(" Error al enviar notificación: ${error.message}")
            else
                println(" Notificación de bienvenida enviada a $nombre")
        }
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

        refNotif.setValue(notificacion) { error, _ ->
            if (error != null)
                println(" Error al enviar notificación: ${error.message}")
            else
                println(" Notificación de quiz enviada")
        }
    }
}