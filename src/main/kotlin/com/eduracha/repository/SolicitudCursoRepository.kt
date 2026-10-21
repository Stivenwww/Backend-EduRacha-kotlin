package com.eduracha.repository

import com.eduracha.utils.lecturaFirebase
import com.eduracha.utils.reanudarConError

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
    suspend fun buscarCursoPorCodigo(codigo: String): Curso? = lecturaFirebase { cont ->
        refCursos.orderByChild("codigo").equalTo(codigo.trim())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val nodo = snapshot.children.firstOrNull()
                    // El id real es la clave del nodo: algunos cursos no guardan el campo "id"
                    val curso = nodo?.getValue(Curso::class.java)?.let { c ->
                        if (c.id.isNullOrBlank()) c.copy(id = nodo.key) else c
                    }
                    cont.resume(curso)
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudCursoRepository.kt")
                }
            }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }

    /**
     * Estado de la solicitud que impide enviar otra (PENDIENTE o ACEPTADA), o null si puede enviarla.
     * Una solicitud RECHAZADA ya no bloquea: el estudiante puede volver a pedir el ingreso.
     * Se leen los valores crudos para que un registro antiguo con otro formato no tumbe la verificación.
     */
    suspend fun solicitudQueBloquea(estudianteId: String, cursoId: String): String? =
        lecturaFirebase { cont ->
            refSolicitudes.orderByChild("estudianteId").equalTo(estudianteId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        val bloqueante = snapshot.children.mapNotNull { hijo ->
                            val curso = hijo.child("cursoId").getValue(String::class.java)
                            val estado = hijo.child("estado").value?.toString()?.uppercase() ?: "PENDIENTE"
                            if (curso == cursoId && estado in setOf("PENDIENTE", "ACEPTADA")) estado else null
                        }.firstOrNull()
                        cont.resume(bloqueante)
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudCursoRepository.kt")
                }
            }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    /** El estudiante ya figura como activo en cursos/{cursoId}/estudiantes. */
    suspend fun estaInscrito(cursoId: String, estudianteId: String): Boolean = lecturaFirebase { cont ->
        refCursos.child(cursoId).child("estudiantes").child(estudianteId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val estado = snapshot.child("estado").getValue(String::class.java)?.lowercase()
                    cont.resume(snapshot.exists() && estado != "eliminado")
                }
                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }

    // Crear solicitud
    suspend fun crearSolicitud(solicitud: SolicitudCurso): String = lecturaFirebase { cont ->
        val nuevaRef = refSolicitudes.push()
        val id = nuevaRef.key ?: return@lecturaFirebase cont.resumeWithException(
            Exception("No se pudo generar ID de solicitud")
        )

        nuevaRef.setValue(solicitud.copy(id = id)) { error, _ ->
            if (error != null) cont.resumeWithException(error.toException())
            else cont.resume(id)
        }
    }

    // Actualizar estado
    suspend fun actualizarEstadoSolicitud(id: String, estado: EstadoSolicitud, mensaje: String?) =
        lecturaFirebase<Unit> { cont ->
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
    ) = lecturaFirebase<Unit> { cont ->

        val refEstudiante = refCursos.child(cursoId).child("estudiantes").child(estudianteId)

        val data = mapOf(
            "id" to estudianteId,
            "nombre" to nombre,
            "email" to email,
            "estado" to "activo",
            "fechaIngreso" to System.currentTimeMillis()
        )

        refEstudiante.setValue(data, object : DatabaseReference.CompletionListener {
            override fun onComplete(error: DatabaseError?, ref: DatabaseReference) {
                if (error != null) {
                    cont.resumeWithException(error.toException())
                } else {
                    // Inicializar inscripción con vidas
                    val refInscripcion = db.getReference("inscripciones")
                        .child(cursoId)
                        .child(estudianteId)

                    val inscripcionData = mapOf(
                        "userId" to estudianteId,
                        "cursoId" to cursoId,
                        "estado" to "aprobado",
                        "fechaInscripcion" to System.currentTimeMillis(),
                        "fechaAprobacion" to System.currentTimeMillis(),
                        "vidasActuales" to 5,
                        "vidasMax" to 5,
                        "ultimaRegen" to System.currentTimeMillis(),
                        "intentosHechos" to 0,
                        "experienciaTotal" to 0
                    )

                    refInscripcion.setValue(inscripcionData, object : DatabaseReference.CompletionListener {
                        override fun onComplete(errorInsc: DatabaseError?, ref2: DatabaseReference) {
                            if (errorInsc != null) {
                                println("Error inicializando inscripción: ${errorInsc.message}")
                            } else {
                                println("Inscripción creada para $nombre")
                            }

                           
                            try {
                                com.eduracha.services.ServicioProgreso.inicializarProgresoEstudiante(
                                    estudianteId = estudianteId,
                                    cursoId = cursoId,
                                    nombre = nombre,
                                    email = email
                                )
                                println("Progreso unificado inicializado para $nombre")
                            } catch (e: Exception) {
                                println("Error inicializando progreso: ${e.message}")
                            }

                            cont.resume(Unit)
                        }
                    })
                }
            }
        })
    }

    // Obtener solicitud por ID
    suspend fun obtenerSolicitudPorId(id: String): SolicitudCurso? = lecturaFirebase { cont ->
        refSolicitudes.child(id)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    cont.resume(snapshot.getValue(SolicitudCurso::class.java))
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudCursoRepository.kt")
                }
            }

                override fun onCancelled(error: DatabaseError) {
                    cont.resumeWithException(error.toException())
                }
            })
    }

    // Solicitudes pendientes por docente
    suspend fun obtenerSolicitudesPendientesPorDocente(docenteId: String): List<SolicitudCurso> =
        lecturaFirebase { cont ->
            refSolicitudes.orderByChild("estado").equalTo(EstadoSolicitud.PENDIENTE.name)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
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
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudCursoRepository.kt")
                }
            }

                    override fun onCancelled(error: DatabaseError) {
                        cont.resumeWithException(error.toException())
                    }
                })
        }

    // Solicitudes por estudiante
    suspend fun obtenerSolicitudesPorEstudiante(estudianteId: String): List<SolicitudCurso> =
        lecturaFirebase { cont ->
            refSolicitudes.orderByChild("estudianteId").equalTo(estudianteId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                try {
                        val lista = snapshot.children.mapNotNull {
                            it.getValue(SolicitudCurso::class.java)
                        }
                        cont.resume(lista)
                } catch (e: Exception) {
                    reanudarConError(cont, e, "SolicitudCursoRepository.kt")
                }
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
    ) = lecturaFirebase<Unit> { cont ->

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