package com.eduracha.repository

import com.eduracha.utils.lecturaFirebase
import com.eduracha.utils.reanudarConError

import com.eduracha.models.SolicitudMasPreguntas
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SolicitudPreguntasRepository {
    
    private val database = FirebaseDatabase.getInstance()
    private val ref = database.getReference("solicitudes_mas_preguntas")
    private val refCursos = database.getReference("cursos")
    
    
     //Crear solicitud de más preguntas
     
    suspend fun crearSolicitud(solicitud: SolicitudMasPreguntas): String = 
        lecturaFirebase { cont ->
            val nuevoRef = ref.push()
            val id = nuevoRef.key ?: return@lecturaFirebase cont.resumeWithException(
                Exception("No se pudo generar ID de solicitud")
            )
            
            val nuevaSolicitud = solicitud.copy(
                id = id,
                fechaSolicitud = System.currentTimeMillis()
            )
            
            nuevoRef.setValue(nuevaSolicitud, DatabaseReference.CompletionListener { error, _ ->
                if (error != null) cont.resumeWithException(error.toException())
                else {
                    println(" Solicitud de más preguntas creada: ID=$id, Tema=${solicitud.temaId}")
                    cont.resume(id)
                }
            })
        }
    
    
     //Verificar si ya existe una solicitud pendiente para evitar duplicados
     
    suspend fun existeSolicitudPendiente(
        cursoId: String,
        temaId: String,
        estudianteId: String
    ): Boolean = lecturaFirebase { cont ->
        ref.orderByChild("cursoId").equalTo(cursoId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val existe = snapshot.children.any { child ->
                        val solicitud = child.getValue(SolicitudMasPreguntas::class.java)
                        solicitud?.temaId == temaId &&
                        solicitud.estudianteId == estudianteId &&
                        solicitud.estado == "pendiente"
                    }
                    cont.resume(existe)
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudPreguntasRepository.kt")
                }
            }
                
                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }
    
    
     // Contar cuántos estudiantes diferentes solicitaron más preguntas para un tema
     
    suspend fun contarSolicitudesPorTema(
        cursoId: String,
        temaId: String
    ): Int = lecturaFirebase { cont ->
        ref.orderByChild("cursoId").equalTo(cursoId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val estudiantesUnicos = snapshot.children
                        .mapNotNull { child -> 
                            child.getValue(SolicitudMasPreguntas::class.java)
                        }
                        .filter { it.temaId == temaId && it.estado == "pendiente" }
                        .map { it.estudianteId }
                        .toSet()
                        .size
                    
                    cont.resume(estudiantesUnicos)
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudPreguntasRepository.kt")
                }
            }
                
                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }
    
     // Obtener solicitudes pendientes por docente
    suspend fun obtenerSolicitudesPorDocente(docenteId: String): List<SolicitudMasPreguntas> =
        lecturaFirebase { cont ->
            // Primero obtener cursos del docente
            refCursos.orderByChild("docenteId").equalTo(docenteId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(cursosSnapshot: DataSnapshot) {
                try {
                        val cursosIds = cursosSnapshot.children.mapNotNull { it.key }
                        
                        if (cursosIds.isEmpty()) {
                            cont.resume(emptyList())
                            return
                        }
                        
                        // Luego obtener solicitudes de esos cursos
                        ref.orderByChild("estado").equalTo("pendiente")
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(snapshot: DataSnapshot) {
                                    val solicitudes = snapshot.children
                                        .mapNotNull { it.getValue(SolicitudMasPreguntas::class.java) }
                                        .filter { it.cursoId in cursosIds }
                                        .sortedByDescending { it.fechaSolicitud }
                                    
                                    cont.resume(solicitudes)
                                }
                                
                                override fun onCancelled(error: DatabaseError) {
                                    cont.resumeWithException(error.toException())
                                }
                            })
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudPreguntasRepository.kt")
                }
            }
                    
                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }
    
     // Obtener solicitudes agrupadas por tema (para mostrar resumen al docente)
    suspend fun obtenerResumenSolicitudesPorDocente(
        docenteId: String
    ): List<ResumenSolicitudesTema> = lecturaFirebase { cont ->
        refCursos.orderByChild("docenteId").equalTo(docenteId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(cursosSnapshot: DataSnapshot) {
                try {
                    val cursosIds = cursosSnapshot.children.mapNotNull { it.key }
                    
                    if (cursosIds.isEmpty()) {
                        cont.resume(emptyList())
                        return
                    }
                    
                    ref.orderByChild("estado").equalTo("pendiente")
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                val solicitudes = snapshot.children
                                    .mapNotNull { it.getValue(SolicitudMasPreguntas::class.java) }
                                    .filter { it.cursoId in cursosIds }
                                
                                // Agrupar por curso y tema
                                val agrupadas = solicitudes
                                    .groupBy { "${it.cursoId}|${it.temaId}" }
                                    .map { (_, grupo) ->
                                        val primera = grupo.first()
                                        ResumenSolicitudesTema(
                                            cursoId = primera.cursoId,
                                            temaId = primera.temaId,
                                            tituloTema = primera.tituloTema ?: "Sin título",
                                            estudiantesAfectados = grupo.size,
                                            preguntasActuales = primera.preguntasActuales,
                                            fechaPrimeraSolicitud = grupo.minOf { it.fechaSolicitud }
                                        )
                                    }
                                    .sortedByDescending { it.estudiantesAfectados }
                                
                                cont.resume(agrupadas)
                            }
                            
                            override fun onCancelled(error: DatabaseError) {
                                cont.resumeWithException(error.toException())
                            }
                        })
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudPreguntasRepository.kt")
                }
            }
                
                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }
    
    /**
     * Marcar solicitud como atendida
     */
    suspend fun marcarAtendida(solicitudId: String) = 
        lecturaFirebase<Unit> { cont ->
            val updates = mapOf(
                "estado" to "atendida",
                "fechaAtencion" to System.currentTimeMillis()
            )
            
            ref.child(solicitudId).updateChildren(updates, DatabaseReference.CompletionListener { error, _ ->
                if (error != null) cont.resumeWithException(error.toException())
                else {
                    println("Solicitud $solicitudId marcada como atendida")
                    cont.resume(Unit)
                }
            })
        }
    
    /**
     * Marcar todas las solicitudes de un tema como atendidas
     * (útil cuando el docente agrega preguntas nuevas)
     */
    suspend fun marcarAtendidasPorTema(
        cursoId: String,
        temaId: String
    ) = lecturaFirebase<Unit> { cont ->
        ref.orderByChild("cursoId").equalTo(cursoId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val solicitudesDelTema = snapshot.children
                        .mapNotNull { child ->
                            val solicitud = child.getValue(SolicitudMasPreguntas::class.java)
                            if (solicitud?.temaId == temaId && solicitud.estado == "pendiente") {
                                child.key to solicitud
                            } else null
                        }
                    
                    if (solicitudesDelTema.isEmpty()) {
                        cont.resume(Unit)
                        return
                    }
                    
                    val updates = mutableMapOf<String, Any>()
                    solicitudesDelTema.forEach { (key, _) ->
                        updates["$key/estado"] = "atendida"
                        updates["$key/fechaAtencion"] = System.currentTimeMillis()
                    }
                    
                    ref.updateChildren(updates, DatabaseReference.CompletionListener { error, _ ->
                        if (error != null) cont.resumeWithException(error.toException())
                        else {
                            println("${solicitudesDelTema.size} solicitudes marcadas como atendidas para tema $temaId")
                            cont.resume(Unit)
                        }
                    })
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudPreguntasRepository.kt")
                }
            }
                
                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }
    
     //Obtener una solicitud por ID
    suspend fun obtenerSolicitudPorId(solicitudId: String): SolicitudMasPreguntas? = 
        lecturaFirebase { cont ->
            ref.child(solicitudId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        cont.resume(snapshot.getValue(SolicitudMasPreguntas::class.java))
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudPreguntasRepository.kt")
                }
            }
                    
                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }
    
     // Eliminar solicitudes antiguas ya atendidas (limpieza de base de datos)
    suspend fun limpiarSolicitudesAntiguas(diasAntiguedad: Int = 30) = 
        lecturaFirebase<Unit> { cont ->
            val fechaLimite = System.currentTimeMillis() - (diasAntiguedad * 24 * 60 * 60 * 1000L)
            
            ref.orderByChild("estado").equalTo("atendida")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        val antiguasIds = snapshot.children
                            .mapNotNull { child ->
                                val solicitud = child.getValue(SolicitudMasPreguntas::class.java)
                                if (solicitud?.fechaAtencion ?: 0 < fechaLimite) {
                                    child.key
                                } else null
                            }
                        
                        if (antiguasIds.isEmpty()) {
                            cont.resume(Unit)
                            return
                        }
                        
                        val updates = mutableMapOf<String, Any?>()
                        antiguasIds.forEach { id ->
                            updates[id] = null // null elimina el nodo
                        }
                        
                        ref.updateChildren(updates, DatabaseReference.CompletionListener { error, _ ->
                            if (error != null) cont.resumeWithException(error.toException())
                            else {
                                println(" ${antiguasIds.size} solicitudes antiguas eliminadas")
                                cont.resume(Unit)
                            }
                        })
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudPreguntasRepository.kt")
                }
            }
                    
                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }
}


@kotlinx.serialization.Serializable
data class ResumenSolicitudesTema(
    val cursoId: String,
    val temaId: String,
    val tituloTema: String,
    val estudiantesAfectados: Int,
    val preguntasActuales: Int,
    val fechaPrimeraSolicitud: Long
)