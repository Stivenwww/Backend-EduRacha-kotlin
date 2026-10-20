package com.eduracha.repository

import com.eduracha.utils.lecturaFirebase
import com.eduracha.utils.reanudarConError

import com.eduracha.models.Usuario
import com.eduracha.models.PerfilUsuario
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UsuarioRepository {
    private val database = FirebaseDatabase.getInstance()

    suspend fun obtenerUsuario(uid: String): Usuario? = lecturaFirebase { cont ->
        database.getReference("usuarios/$uid")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    cont.resume(snapshot.getValue(Usuario::class.java))
                } catch (e: Exception) {
                    reanudarConError(cont, e, "UsuarioRepository.kt")
                }
            }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }
}
