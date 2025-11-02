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

    // Buscar curso por código
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

    // Verificar si ya existe una solicitud
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

    // Crear solicitud
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

    // Actualizar estado
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

    // Agregar estudiante con sus datos al curso
    
suspend fun agregarEstudianteACurso(
    cursoId: String,
    estudianteId: String,
    nombre: String,
    email: String
) = suspendCancellableCoroutine<Unit> { cont ->
    val refEstudiante = refCursos.child(cursoId).child("estudiantes").child(estudianteId)

    val data = mapOf(
        "id" to estudianteId,
        "nombre" to nombre,
        "email" to email
    )

    refEstudiante.setValue(data) { error, _ ->
        if (error != null) {
            cont.resumeWithException(error.toException())
        } else {
            //  Inicializar inscripción con vidas
            val refInscripcion = db.getReference("inscripciones")
                .child(cursoId)
                .child(estudianteId)

            val inscripcionData = mapOf(
                "userId" to estudianteId,
                "cursoId" to cursoId,
                "estado" to "aprobado",
                "vidasActuales" to 5,
                "vidasMax" to 5,
                "ultimaRegen" to System.currentTimeMillis(),
                "intentosHechos" to 0
            )

            refInscripcion.setValue(inscripcionData) { errorInsc, _ ->
                if (errorInsc != null) {
                    println("Error inicializando inscripción: ${errorInsc.message}")
                }

                // Inicializar progreso y racha del estudiante
                try {
                    com.eduracha.services.ServicioProgreso.inicializarDatosEstudiante(
                        estudianteId = estudianteId,
                        cursoId = cursoId,
                        nombre = nombre,
                        email = email
                    )
                } catch (e: Exception) {
                    println("Error inicializando progreso: ${e.message}")
                }

                cont.resume(Unit)
            }
        }
    }
}

    // Obtener solicitud por ID
    suspend fun obtenerSolicitudPorId(id: String): SolicitudCurso? = suspendCancellableCoroutine { cont ->
        refSolicitudes.child(id)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    cont.resume(snapshot.getValue(SolicitudCurso::class.java))
                }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }

    // Solicitudes pendientes por docente
    suspend fun obtenerSolicitudesPendientesPorDocente(docenteId: String): List<SolicitudCurso> =
        suspendCancellableCoroutine { cont ->
            refSolicitudes.orderByChild("estado").equalTo(EstadoSolicitud.PENDIENTE.name)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val solicitudes = snapshot.children.mapNotNull { it.getValue(SolicitudCurso::class.java) }
                        refCursos.addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(cursosSnap: DataSnapshot) {
                                val cursosDocente = cursosSnap.children.filter {
                                    it.child("docenteId").getValue(String::class.java) == docenteId
                                }.mapNotNull { it.key }

                                val filtradas = solicitudes.filter { it.cursoId in cursosDocente }
                                cont.resume(filtradas)
                            }

                            override fun onCancelled(error: DatabaseError) {
                                cont.resumeWithException(error.toException())
                            }
                        })
                    }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    // Solicitudes por estudiante
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

        // Cambiar estado del estudiante dentro del curso
suspend fun cambiarEstadoEstudiante(
    cursoId: String,
    estudianteId: String,
    nuevoEstado: String
) = suspendCancellableCoroutine<Unit> { cont ->

    val ref = refCursos.child(cursoId).child("estudiantes").child(estudianteId)

    if (nuevoEstado == "eliminado") {
        ref.removeValue { error, _ ->
            if (error != null) cont.resumeWithException(error.toException())
            else cont.resume(Unit)
        }
    } else {
        val updates = mapOf(
            "estado" to nuevoEstado
        )

        ref.updateChildren(updates) { error, _ ->
            if (error != null) cont.resumeWithException(error.toException())
            else cont.resume(Unit)
        }
    }
}

}
