package com.eduracha.repository

import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class RankingItem(
    val estudianteId: String,
    val nombre: String? = null,
    val experiencia: Int = 0,
    val rachaDias: Int = 0,
    val vidas: Int = 0,
    val cursoId: String? = null
)

class RachaRepository {

    private val db = FirebaseDatabase.getInstance()
    private val refUsuarios = db.getReference("usuarios")
    private val refCursos = db.getReference("cursos")

    
     // Obtener ranking por curso con filtro
     
    suspend fun obtenerRankingPorCurso(
        cursoId: String, 
        filtro: String
    ): List<RankingItem> = suspendCancellableCoroutine { cont ->
        
        println(" [RachaRepository] Obteniendo ranking")
        println("   - CursoId: $cursoId")
        println("   - Filtro: $filtro")
        
        // 1️ Obtener estudiantes del curso
        refCursos.child(cursoId).child("estudiantes")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(estudiantesSnapshot: DataSnapshot) {
                    
                    if (!estudiantesSnapshot.exists()) {
                        println(" No hay estudiantes en el curso")
                        cont.resume(emptyList())
                        return
                    }

                    val listaRanking = mutableListOf<RankingItem>()
                    var procesados = 0
                    val total = estudiantesSnapshot.childrenCount.toInt()

                    println("👥 Total estudiantes: $total")

                    if (total == 0) {
                        println(" No hay estudiantes para procesar")
                        cont.resume(emptyList())
                        return
                    }

                    estudiantesSnapshot.children.forEach { estudianteSnap ->
                        val uid = estudianteSnap.key
                        if (uid.isNullOrBlank()) {
                            procesados++
                            if (procesados == total) {
                                cont.resume(ordenarPorFiltro(listaRanking, filtro))
                            }
                            return@forEach
                        }

                        val nombreCurso = estudianteSnap.child("nombre")
                            .getValue(String::class.java) ?: "Estudiante"
                        val estado = estudianteSnap.child("estado")
                            .getValue(String::class.java)

                        // Solo estudiantes activos
                        if (estado != "activo") {
                            println("Saltando $uid - Estado: $estado")
                            procesados++
                            if (procesados == total) {
                                cont.resume(ordenarPorFiltro(listaRanking, filtro))
                            }
                            return@forEach
                        }

                        // Obtener progreso del estudiante
                        refUsuarios.child(uid)
                            .child("cursos")
                            .child(cursoId)
                            .child("progreso")
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(progresoSnap: DataSnapshot) {
                                    
                                    val xp = progresoSnap.child("experiencia")
                                        .getValue(Int::class.java) ?: 0
                                    val racha = progresoSnap.child("diasConsecutivos")
                                        .getValue(Int::class.java) ?: 0
                                    val vidas = progresoSnap.child("vidas")
                                        .getValue(Int::class.java) ?: 5
                                    val nombreProg = progresoSnap.child("nombre")
                                        .getValue(String::class.java)

                                    println("$uid: XP=$xp, Racha=$racha, Vidas=$vidas")

                                    listaRanking.add(
                                        RankingItem(
                                            estudianteId = uid,
                                            nombre = nombreProg ?: nombreCurso,
                                            experiencia = xp,
                                            rachaDias = racha,
                                            vidas = vidas,
                                            cursoId = cursoId
                                        )
                                    )

                                    procesados++
                                    if (procesados == total) {
                                        val ordenado = ordenarPorFiltro(listaRanking, filtro)
                                        println("Ranking completado: ${ordenado.size} estudiantes")
                                        cont.resume(ordenado)
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    println("Error progreso $uid: ${error.message}")
                                    procesados++
                                    if (procesados == total) {
                                        cont.resume(ordenarPorFiltro(listaRanking, filtro))
                                    }
                                }
                            })
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    println("Error obteniendo estudiantes: ${error.message}")
                    cont.resumeWithException(error.toException())
                }
            })
    }

    /**
     * Ordenar ranking según filtro
     */
    private fun ordenarPorFiltro(lista: List<RankingItem>, filtro: String): List<RankingItem> {
        val ordenado = when (filtro.lowercase()) {
            "experiencia" -> lista.sortedByDescending { it.experiencia }
            "racha" -> lista.sortedByDescending { it.rachaDias }
            "vidas" -> lista.sortedByDescending { it.vidas }
            else -> lista.sortedByDescending { it.experiencia }
        }
        
        if (ordenado.isNotEmpty()) {
            println(" Top 3: ${ordenado.take(3).map { 
                "${it.nombre} (${getValorSegunFiltro(it, filtro)})" 
            }}")
        }
        return ordenado
    }

    private fun getValorSegunFiltro(item: RankingItem, filtro: String): Any {
        return when (filtro.lowercase()) {
            "experiencia" -> "${item.experiencia} XP"
            "racha" -> "${item.rachaDias} días"
            "vidas" -> "${item.vidas} "
            else -> "${item.experiencia} XP"
        }
    }

    /**
     *  Ranking general (todos los cursos)
     */
    suspend fun obtenerRankingGeneral(filtro: String): List<RankingItem> =
        suspendCancellableCoroutine { cont ->
            
            println(" Obteniendo ranking general, filtro: $filtro")
            
            refCursos.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(cursosSnapshot: DataSnapshot) {
                    
                    val listaTotal = mutableListOf<RankingItem>()
                    var cursosProcesados = 0
                    val totalCursos = cursosSnapshot.childrenCount.toInt()

                    if (totalCursos == 0) {
                        cont.resume(emptyList())
                        return
                    }

                    cursosSnapshot.children.forEach { cursoSnap ->
                        val cursoId = cursoSnap.key ?: return@forEach
                        val estudiantesSnap = cursoSnap.child("estudiantes")

                        if (!estudiantesSnap.exists()) {
                            cursosProcesados++
                            if (cursosProcesados == totalCursos) {
                                cont.resume(ordenarPorFiltro(listaTotal, filtro))
                            }
                            return@forEach
                        }

                        var estudiantesProcesados = 0
                        val totalEstudiantes = estudiantesSnap.childrenCount.toInt()

                        if (totalEstudiantes == 0) {
                            cursosProcesados++
                            if (cursosProcesados == totalCursos) {
                                cont.resume(ordenarPorFiltro(listaTotal, filtro))
                            }
                            return@forEach
                        }

                        estudiantesSnap.children.forEach { estudianteSnap ->
                            val uid = estudianteSnap.key
                            if (uid.isNullOrBlank()) {
                                estudiantesProcesados++
                                if (estudiantesProcesados == totalEstudiantes) {
                                    cursosProcesados++
                                    if (cursosProcesados == totalCursos) {
                                        cont.resume(ordenarPorFiltro(listaTotal, filtro))
                                    }
                                }
                                return@forEach
                            }

                            val nombre = estudianteSnap.child("nombre")
                                .getValue(String::class.java) ?: "Estudiante"
                            val estado = estudianteSnap.child("estado")
                                .getValue(String::class.java)

                            if (estado != "activo") {
                                estudiantesProcesados++
                                if (estudiantesProcesados == totalEstudiantes) {
                                    cursosProcesados++
                                    if (cursosProcesados == totalCursos) {
                                        cont.resume(ordenarPorFiltro(listaTotal, filtro))
                                    }
                                }
                                return@forEach
                            }

                            refUsuarios.child(uid).child("cursos")
                                .child(cursoId).child("progreso")
                                .addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(progresoSnap: DataSnapshot) {
                                        
                                        val xp = progresoSnap.child("experiencia")
                                            .getValue(Int::class.java) ?: 0
                                        val racha = progresoSnap.child("diasConsecutivos")
                                            .getValue(Int::class.java) ?: 0
                                        val vidas = progresoSnap.child("vidas")
                                            .getValue(Int::class.java) ?: 5

                                        listaTotal.add(
                                            RankingItem(
                                                estudianteId = uid,
                                                nombre = nombre,
                                                experiencia = xp,
                                                rachaDias = racha,
                                                vidas = vidas,
                                                cursoId = cursoId
                                            )
                                        )

                                        estudiantesProcesados++
                                        if (estudiantesProcesados == totalEstudiantes) {
                                            cursosProcesados++
                                            if (cursosProcesados == totalCursos) {
                                                cont.resume(ordenarPorFiltro(listaTotal, filtro))
                                            }
                                        }
                                    }

                                    override fun onCancelled(error: DatabaseError) {
                                        estudiantesProcesados++
                                        if (estudiantesProcesados == totalEstudiantes) {
                                            cursosProcesados++
                                            if (cursosProcesados == totalCursos) {
                                                cont.resume(ordenarPorFiltro(listaTotal, filtro))
                                            }
                                        }
                                    }
                                })
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    println(" Error en ranking general: ${error.message}")
                    cont.resumeWithException(error.toException())
                }
            })
        }
}