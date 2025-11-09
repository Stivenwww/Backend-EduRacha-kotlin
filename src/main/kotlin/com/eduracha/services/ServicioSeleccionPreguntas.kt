package com.eduracha.services

import com.eduracha.models.*
import com.eduracha.repository.PreguntaRepository
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ServicioSeleccionPreguntas(
    private val preguntaRepo: PreguntaRepository
) {
    
    private val db = FirebaseDatabase.getInstance()
    
    /**
     * Selecciona preguntas para un quiz, evitando repeticiones para el estudiante
     */
    suspend fun seleccionarPreguntasParaQuiz(
        cursoId: String,
        temaId: String,
        estudianteId: String,
        cantidadRequerida: Int = 10,
        preguntasYaVistas: Set<String> = emptySet()
    ): List<Pregunta> {
        
        // 1. Obtener todas las preguntas aprobadas del tema
        val todasLasPreguntas = preguntaRepo.obtenerPreguntasPorCursoYEstado(cursoId, "aprobada")
            .filter { it.temaId == temaId }
        
        if (todasLasPreguntas.isEmpty()) {
            throw IllegalStateException("No hay preguntas disponibles para este tema")
        }
        
        // 2. Filtrar preguntas que el estudiante NO ha visto
        val preguntasNoVistas = todasLasPreguntas.filter { pregunta ->
            pregunta.id !in preguntasYaVistas
        }
        
        // 3. Si no hay suficientes preguntas nuevas, incluir preguntas ya vistas
        val preguntasDisponibles = if (preguntasNoVistas.size >= cantidadRequerida) {
            preguntasNoVistas
        } else {
            todasLasPreguntas.sortedBy { pregunta ->
                pregunta.usadaPorEstudiantes[estudianteId] ?: 0
            }
        }
        
        // 4. Seleccionar y mezclar
        val preguntasSeleccionadas = preguntasDisponibles
            .shuffled()
            .take(cantidadRequerida)
        
        // 5. Registrar uso de preguntas
        preguntasSeleccionadas.forEach { pregunta ->
            registrarUsoPregunta(pregunta.id ?: "", estudianteId)
        }
        
        return preguntasSeleccionadas
    }
    
    /**
     * Registra que un estudiante ha usado una pregunta en un quiz
     */
    private suspend fun registrarUsoPregunta(
        preguntaId: String, 
        estudianteId: String
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val ref = db.getReference("preguntas/$preguntaId/usadaPorEstudiantes/$estudianteId")
        
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val vecesUsada = snapshot.getValue(Int::class.java) ?: 0
                
                ref.setValue(vecesUsada + 1, DatabaseReference.CompletionListener { error, _ ->
                    if (error != null) {
                        cont.resumeWithException(Exception(error.message))
                    } else {
                        cont.resume(Unit)
                    }
                })
            }
            
            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }
    
    /**
     * Obtiene preguntas ya vistas por el estudiante en un tema
     */
    suspend fun obtenerPreguntasVistas(
        cursoId: String,
        temaId: String,
        estudianteId: String
    ): Set<String> = suspendCancellableCoroutine { cont ->
        val ref = db.getReference("usuarios/$estudianteId/perfil/cursos/$cursoId/temasCompletados/$temaId/preguntasVistas")
        
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val preguntas = snapshot.children.mapNotNull { 
                    it.getValue(String::class.java) 
                }.toSet()
                cont.resume(preguntas)
            }
            
            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }
    
    /**
     * Actualiza el conjunto de preguntas vistas por el estudiante
     */
    suspend fun actualizarPreguntasVistas(
        cursoId: String,
        temaId: String,
        estudianteId: String,
        preguntasIds: List<String>
    ) = suspendCancellableCoroutine<Unit> { cont ->
        val ref = db.getReference("usuarios/$estudianteId/perfil/cursos/$cursoId/temasCompletados/$temaId")
        
        ref.child("preguntasVistas").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val actuales = snapshot.children.mapNotNull { 
                    it.getValue(String::class.java) 
                }.toMutableSet()
                
                actuales.addAll(preguntasIds)
                
                ref.child("preguntasVistas").setValue(actuales.toList(), DatabaseReference.CompletionListener { error, _ ->
                    if (error != null) {
                        cont.resumeWithException(Exception(error.message))
                    } else {
                        cont.resume(Unit)
                    }
                })
            }
            
            override fun onCancelled(error: DatabaseError) {
                cont.resumeWithException(error.toException())
            }
        })
    }
    
    /**
     * Verifica si hay suficientes preguntas únicas para todos los quizzes requeridos
     */
    suspend fun verificarSuficientesPreguntas(
        cursoId: String,
        temaId: String,
        quizzesRequeridos: Int,
        preguntasPorQuiz: Int = 10
    ): ValidacionPreguntas {
        val preguntas = preguntaRepo.obtenerPreguntasPorCursoYEstado(cursoId, "aprobada")
            .filter { it.temaId == temaId }
        
        val preguntasTotalesNecesarias = quizzesRequeridos * preguntasPorQuiz
        val preguntasDisponibles = preguntas.size
        
        val suficientes = preguntasDisponibles >= preguntasTotalesNecesarias
        
        return ValidacionPreguntas(
            suficientes = suficientes,
            preguntasDisponibles = preguntasDisponibles,
            preguntasNecesarias = preguntasTotalesNecesarias,
            mensaje = if (suficientes) {
                "Hay suficientes preguntas para todos los quizzes"
            } else {
                "Se necesitan $preguntasTotalesNecesarias preguntas, pero solo hay $preguntasDisponibles disponibles. " +
                "Algunas preguntas se repetirán."
            }
        )
    }
}

@kotlinx.serialization.Serializable
data class ValidacionPreguntas(
    val suficientes: Boolean,
    val preguntasDisponibles: Int,
    val preguntasNecesarias: Int,
    val mensaje: String
)