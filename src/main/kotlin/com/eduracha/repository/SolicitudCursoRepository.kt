package com.eduracha.repository

import com.eduracha.models.*
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SolicitudCursoRepository {
    private val db = FirebaseDatabase.getInstance()
    private val refSolicitudes = db.getReference("solicitudes")
    private val refCursos = db.getReference("cursos")

    //  Buscar curso por código
    suspend fun buscarCursoPorCodigo(codigo: String): Curso? = suspendCancellableCoroutine { cont ->
        refCursos.orderByChild("codigo").equalTo(codigo)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val curso = snapshot.children.firstOrNull()?.getValue(Curso::class.java)
                    cont.resume(curso)
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }

    //  Verificar si ya existe una solicitud
    suspend fun verificarSolicitudExistente(estudianteId: String, cursoId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            refSolicitudes.orderByChild("estudianteId").equalTo(estudianteId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val existe = snapshot.children.any {
                            val solicitud = it.getValue(SolicitudCurso::class.java)
                            solicitud?.cursoId == cursoId
                        }
                        cont.resume(existe)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    // Crear solicitud (sin await)
    suspend fun crearSolicitud(solicitud: SolicitudCurso): String = suspendCancellableCoroutine { cont ->
        val nuevaRef = refSolicitudes.push()
        val id = nuevaRef.key ?: return@suspendCancellableCoroutine cont.resumeWithException(
            Exception("No se pudo generar ID de solicitud")
        )

        nuevaRef.setValue(solicitud.copy(id = id)) { error, _ ->
            if (error != null) cont.resumeWithException(error.toException())
            else cont.resume(id)
        }
    }

    //  Solicitudes pendientes
    suspend fun obtenerSolicitudesPendientesPorDocente(docenteId: String): List<SolicitudCurso> =
        suspendCancellableCoroutine { cont ->
            refSolicitudes.orderByChild("estado").equalTo(EstadoSolicitud.PENDIENTE.name)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val lista = snapshot.children.mapNotNull {
                            it.getValue(SolicitudCurso::class.java)
                        }.filter { solicitud ->
                            solicitud.cursoId.isNotEmpty()
                        }
                        cont.resume(lista)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    //  Solicitudes por estudiante
    suspend fun obtenerSolicitudesPorEstudiante(estudianteId: String): List<SolicitudCurso> =
        suspendCancellableCoroutine { cont ->
            refSolicitudes.orderByChild("estudianteId").equalTo(estudianteId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val lista = snapshot.children.mapNotNull {
                            it.getValue(SolicitudCurso::class.java)
                        }
                        cont.resume(lista)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    //  Actualizar estado
    suspend fun actualizarEstadoSolicitud(id: String, estado: EstadoSolicitud, mensaje: String?) =
        suspendCancellableCoroutine<Unit> { cont ->
            val updates = mapOf(
                "estado" to estado.name,
                "fechaRespuesta" to System.currentTimeMillis().toString(),
                "mensaje" to mensaje
            )

            refSolicitudes.child(id).updateChildren(updates) { error, _ ->
                if (error != null) cont.resumeWithException(error.toException())
                else cont.resume(Unit)
            }
        }

    //  Agregar estudiante al curso
    suspend fun agregarEstudianteACurso(cursoId: String, estudianteId: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            val refEstudiantes = refCursos.child(cursoId).child("estudiantes")
            val nuevoRef = refEstudiantes.push()

            nuevoRef.setValue(estudianteId) { error, _ ->
                if (error != null) cont.resumeWithException(error.toException())
                else cont.resume(Unit)
            }
        }

    //  Obtener una solicitud por ID
    suspend fun obtenerSolicitudPorId(id: String): SolicitudCurso? = suspendCancellableCoroutine { cont ->
        refSolicitudes.child(id).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                cont.resume(snapshot.getValue(SolicitudCurso::class.java))
            }

            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }
}
