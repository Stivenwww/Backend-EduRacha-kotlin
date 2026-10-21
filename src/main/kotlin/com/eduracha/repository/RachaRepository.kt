package com.eduracha.repository

import com.eduracha.utils.lecturaFirebase
import com.eduracha.utils.reanudarConError

import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /** Lee un nodo de Firebase una sola vez (null si no existe o no se pudo leer). */
    private suspend fun leer(ref: DatabaseReference): DataSnapshot? = try {
        lecturaFirebase { cont ->
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) { cont.resume(snapshot) }
                override fun onCancelled(error: DatabaseError) { cont.resume(null) }
            })
        }
    } catch (e: Exception) {
        println("Lectura fallida ${ref.path}: ${e.message}")
        null
    }

    private fun entero(snap: DataSnapshot, campo: String, defecto: Int = 0): Int =
        when (val v = snap.child(campo).value) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: defecto
            else -> defecto
        }

    /** Estudiantes del curso que cuentan para el ranking (todos salvo los eliminados o inactivos). */
    private fun estudiantesActivos(estudiantes: DataSnapshot): List<Pair<String, String>> =
        estudiantes.children.mapNotNull { e ->
            val uid = e.key?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val estado = (e.child("estado").value as? String)?.lowercase()
            if (estado in setOf("eliminado", "inactivo", "bloqueado")) return@mapNotNull null
            uid to ((e.child("nombre").value as? String)?.takeIf { it.isNotBlank() } ?: "Estudiante")
        }

    /**
     * Ranking de un curso. Lee el progreso real de cada estudiante en
     * usuarios/{uid}/cursos/{cursoId}/progreso. Los que aún no tienen progreso aparecen con 0.
     */
    suspend fun obtenerRankingPorCurso(cursoId: String, filtro: String): List<RankingItem> = coroutineScope {
        val estudiantes = leer(refCursos.child(cursoId).child("estudiantes")) ?: return@coroutineScope emptyList()
        val lista = estudiantesActivos(estudiantes).map { (uid, nombreCurso) ->
            async {
                val progreso = leer(refUsuarios.child(uid).child("cursos").child(cursoId).child("progreso"))
                RankingItem(
                    estudianteId = uid,
                    nombre = (progreso?.child("nombre")?.value as? String)?.takeIf { it.isNotBlank() } ?: nombreCurso,
                    experiencia = progreso?.let { entero(it, "experiencia") } ?: 0,
                    rachaDias = progreso?.let { entero(it, "diasConsecutivos") } ?: 0,
                    vidas = progreso?.let { entero(it, "vidas", 5) } ?: 5,
                    cursoId = cursoId
                )
            }
        }.awaitAll()
        ordenarPorFiltro(lista, filtro)
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
     * Ranking general: suma la experiencia de cada estudiante en todos sus cursos
     * (antes aparecía repetido una vez por curso).
     */
    suspend fun obtenerRankingGeneral(filtro: String): List<RankingItem> = coroutineScope {
        val cursos = leer(refCursos) ?: return@coroutineScope emptyList()
        val porCurso = cursos.children.mapNotNull { c -> c.key?.let { it to c.child("estudiantes") } }
            .flatMap { (cursoId, est) -> estudiantesActivos(est).map { (uid, nombre) -> Triple(cursoId, uid, nombre) } }
            .map { (cursoId, uid, nombre) ->
                async {
                    val p = leer(refUsuarios.child(uid).child("cursos").child(cursoId).child("progreso"))
                    RankingItem(
                        estudianteId = uid,
                        nombre = nombre,
                        experiencia = p?.let { entero(it, "experiencia") } ?: 0,
                        rachaDias = p?.let { entero(it, "diasConsecutivos") } ?: 0,
                        vidas = p?.let { entero(it, "vidas", 5) } ?: 5
                    )
                }
            }.awaitAll()
        val combinados = porCurso.groupBy { it.estudianteId }.map { (uid, items) ->
            RankingItem(
                estudianteId = uid,
                nombre = items.first().nombre,
                experiencia = items.sumOf { it.experiencia },
                rachaDias = items.maxOf { it.rachaDias },
                vidas = items.maxOf { it.vidas }
            )
        }
        ordenarPorFiltro(combinados, filtro)
    }
}
