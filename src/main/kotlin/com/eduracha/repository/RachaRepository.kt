package com.eduracha.repository

import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RankingItem(
    val estudianteId: String,
    val nombre: String? = null,
    val experiencia: Int = 0,
    val rachaDias: Int = 0,
    val vidas: Int = 0
)

class RachaRepository {

    private val db = FirebaseDatabase.getInstance()
    private val refRachas = db.getReference("rachas")
    private val refCursos = db.getReference("cursos")

    // Obtener ranking general por curso
    suspend fun obtenerRankingPorCurso(cursoId: String, filtro: String): List<RankingItem> =
    suspendCancellableCoroutine { cont ->
        refRachas.child(cursoId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        cont.resume(emptyList())
                        return
                    }

                    val lista = snapshot.children.mapNotNull { userSnap ->
                        val id = userSnap.key ?: return@mapNotNull null
                        val experiencia = userSnap.child("experiencia").getValue(Int::class.java) ?: 0
                        val racha = userSnap.child("diasConsecutivos").getValue(Int::class.java) ?: 0
                        val vidas = userSnap.child("vidas").getValue(Int::class.java) ?: 0

                        RankingItem(
                            estudianteId = id,
                            experiencia = experiencia,
                            rachaDias = racha,
                            vidas = vidas
                        )
                    }

                    // Orden dinámico según el filtro
                    val ordenado = when (filtro.lowercase()) {
                        "experiencia" -> lista.sortedByDescending { it.experiencia }
                        "racha" -> lista.sortedByDescending { it.rachaDias }
                        "vidas" -> lista.sortedByDescending { it.vidas }
                        else -> lista.sortedByDescending { it.experiencia }
                    }

                    cont.resume(ordenado)
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }
}