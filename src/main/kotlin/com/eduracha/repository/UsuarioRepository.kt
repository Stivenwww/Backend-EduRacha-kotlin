package com.eduracha.repository

import com.eduracha.models.Usuario
import com.eduracha.models.PerfilUsuario
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UsuarioRepository {
    private val database = FirebaseDatabase.getInstance()

    suspend fun obtenerUsuario(uid: String): Usuario? = suspendCancellableCoroutine { cont ->
        database.getReference("usuarios/$uid")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    cont.resume(snapshot.getValue(Usuario::class.java))
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }
}
