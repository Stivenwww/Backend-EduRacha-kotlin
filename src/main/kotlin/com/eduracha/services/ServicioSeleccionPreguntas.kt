package com.eduracha.services

import com.eduracha.models.*
import com.eduracha.repository.PreguntaRepository
import com.eduracha.repository.CursoRepository
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

class ServicioSeleccionPreguntas(
    private val preguntaRepo: PreguntaRepository,
    private val cursoRepo: CursoRepository
) {
    
    private val db = FirebaseDatabase.getInstance()
    
    companion object {
        const val PREGUNTAS_POR_ESTUDIANTE = 2
        const val PREGUNTAS_MINIMAS_ABSOLUTAS = 30
    }
    
    // VALIDACIÓN 1: Control de Cantidad Mínima de Preguntas
    
    /**
     * Valida si un tema cumple con el mínimo de preguntas requerido
     * basándose en el número de estudiantes inscritos
     */
    suspend fun validarMinimoPreguntas(
        cursoId: String,
        temaId: String
    ): EstadisticasTema {
        // 1. Obtener número de estudiantes inscritos
        val estudiantes = cursoRepo.obtenerEstudiantesPorCurso(cursoId)
        val numEstudiantes = estudiantes.size
        
        // 2. Calcular preguntas requeridas
        val preguntasRequeridas = calcularPreguntasRequeridas(numEstudiantes)
        
        // 3. Contar preguntas aprobadas del tema
        val preguntasAprobadas = preguntaRepo
            .obtenerPreguntasPorCursoYEstado(cursoId, "aprobada")
            .filter { it.temaId == temaId }
        
        val preguntasActuales = preguntasAprobadas.size
        
        // 4. Determinar si cumple el mínimo
        val cumpleMinimo = preguntasActuales >= preguntasRequeridas
        
        return EstadisticasTema(
            temaId = temaId,
            preguntasActuales = preguntasActuales,
            preguntasRequeridas = preguntasRequeridas,
            estudiantesInscritos = numEstudiantes,
            cumpleMinimo = cumpleMinimo,
            estadoPublicacion = if (cumpleMinimo) "habilitado" else "bloqueado"
        )
    }
    
     //Calcula las preguntas mínimas requeridas
    private fun calcularPreguntasRequeridas(numEstudiantes: Int): Int {
        return maxOf(
            numEstudiantes * PREGUNTAS_POR_ESTUDIANTE,
            PREGUNTAS_MINIMAS_ABSOLUTAS
        )
    }
    
     //Valida si se puede publicar/habilitar un tema para quizzes
    suspend fun puedePublicarTema(
        cursoId: String,
        temaId: String
    ): ResultadoValidacion {
        val estadisticas = validarMinimoPreguntas(cursoId, temaId)
        
        return if (estadisticas.cumpleMinimo) {
            ResultadoValidacion(
                permitido = true,
                mensaje = "El tema cumple con el mínimo de preguntas requerido",
                detalles = mapOf(
                    "preguntasActuales" to estadisticas.preguntasActuales,
                    "preguntasRequeridas" to estadisticas.preguntasRequeridas,
                    "estudiantes" to estadisticas.estudiantesInscritos
                )
            )
        } else {
            val faltantes = estadisticas.preguntasRequeridas - estadisticas.preguntasActuales
            ResultadoValidacion(
                permitido = false,
                mensaje = "Faltan $faltantes preguntas para habilitar este tema. " +
                         "Actualmente hay ${estadisticas.preguntasActuales} de ${estadisticas.preguntasRequeridas} requeridas.",
                detalles = mapOf(
                    "preguntasActuales" to estadisticas.preguntasActuales,
                    "preguntasRequeridas" to estadisticas.preguntasRequeridas,
                    "faltantes" to faltantes
                )
            )
        }
    }
    
    // VALIDACIÓN 2: No Repetir Preguntas por Estudiante
    
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
            return emptyList()
        }
        
        // 2. Filtrar preguntas que el estudiante NO ha visto
        val preguntasNoVistas = todasLasPreguntas.filter { pregunta ->
            pregunta.id !in preguntasYaVistas
        }
        
        // 3. VALIDACIÓN 2: Si ya vio todas, retornar lista vacía
        // Esto activará la VALIDACIÓN 3 en el QuizService
        if (preguntasNoVistas.isEmpty()) {
            return emptyList()
        }
        
        // 4. Si hay suficientes preguntas nuevas, seleccionar solo de ahí
        val preguntasSeleccionadas = if (preguntasNoVistas.size >= cantidadRequerida) {
            preguntasNoVistas
                .shuffled()
                .take(cantidadRequerida)
        } else {
            // Si no hay suficientes, usar todas las disponibles
            preguntasNoVistas.shuffled()
        }
        
        // 5. Registrar uso de preguntas
        preguntasSeleccionadas.forEach { pregunta ->
            registrarUsoPregunta(pregunta.id ?: "", estudianteId)
        }
        
        return preguntasSeleccionadas
    }
    
     // Verifica si un estudiante ya vio todas las preguntas de un tema
    suspend fun estudianteVioTodasLasPreguntas(
        cursoId: String,
        temaId: String,
        preguntasVistas: Set<String>
    ): Boolean {
        val totalPreguntas = preguntaRepo
            .obtenerPreguntasPorCursoYEstado(cursoId, "aprobada")
            .filter { it.temaId == temaId }
            .size
        
        return preguntasVistas.size >= totalPreguntas && totalPreguntas > 0
    }
    
     //Obtiene estadísticas detalladas de uso de preguntas por estudiante
    suspend fun obtenerEstadisticasUso(
        cursoId: String,
        temaId: String,
        estudianteId: String,
        preguntasVistas: Set<String>
    ): EstadisticasUsoPreguntasResponse {
        val totalDisponibles = preguntaRepo
            .obtenerPreguntasPorCursoYEstado(cursoId, "aprobada")
            .filter { it.temaId == temaId }
            .size
        
        val vistas = preguntasVistas.size
        val noVistas = maxOf(0, totalDisponibles - vistas)
        val porcentajeVisto = if (totalDisponibles > 0) {
            (vistas * 100) / totalDisponibles
        } else 0
        
        return EstadisticasUsoPreguntasResponse(
            totalDisponibles = totalDisponibles,
            preguntasVistas = vistas,
            preguntasNoVistas = noVistas,
            porcentajeVisto = porcentajeVisto,
            agotoBanco = noVistas == 0 && totalDisponibles > 0
        )
    }

    // VALIDACIÓN 3: Detectar Necesidad de Más Preguntas
    
    /**
     * Verifica si se necesita solicitar más preguntas al docente
     * Retorna true si el estudiante está cerca de agotar el banco
     */
    suspend fun necesitaMasPreguntas(
        cursoId: String,
        temaId: String,
        preguntasVistas: Set<String>,
        umbralPorcentaje: Int = 90 // Alertar cuando haya visto el 90%
    ): NecesidadMasPreguntasResponse {
        val estadisticas = obtenerEstadisticasUso(
            cursoId, temaId, "", preguntasVistas
        )
        
        val necesita = estadisticas.porcentajeVisto >= umbralPorcentaje
        val urgente = estadisticas.agotoBanco
        
        return NecesidadMasPreguntasResponse(
            necesita = necesita,
            urgente = urgente,
            porcentajeVisto = estadisticas.porcentajeVisto,
            preguntasRestantes = estadisticas.preguntasNoVistas,
            mensaje = when {
                urgente -> "Ya no hay preguntas nuevas disponibles. Se notificó al docente."
                necesita -> "Quedan pocas preguntas nuevas (${estadisticas.preguntasNoVistas}). El docente será notificado pronto."
                else -> "Aún hay suficientes preguntas disponibles."
            }
        )
    }
    
    
     // Registra que un estudiante ha usado una pregunta en un quiz
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
    
     //Obtiene preguntas ya vistas por el estudiante en un tema
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
    
     // Actualiza el conjunto de preguntas vistas por el estudiante
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
    
     // Verifica si hay suficientes preguntas únicas para todos los quizzes requeridos
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


@kotlinx.serialization.Serializable
data class ResultadoValidacion(
    val permitido: Boolean,
    val mensaje: String,
    val preguntasActuales: Int? = null,
    val preguntasRequeridas: Int? = null,
    val faltantes: Int? = null,
    val detalles: Map<String, @Contextual Any>? = null 
)


@kotlinx.serialization.Serializable
data class EstadisticasSeleccion(
    val totalPreguntasDisponibles: Int,
    val totalSeleccionadas: Int,
    val seleccionPorDificultad: Map<String, Int>,
    val seleccionPorTema: Map<String, Int>
)

@kotlinx.serialization.Serializable
data class EstadisticasUsoPreguntasResponse(
    val totalDisponibles: Int,
    val preguntasVistas: Int,
    val preguntasNoVistas: Int,
    val porcentajeVisto: Int,
    val agotoBanco: Boolean
)

@kotlinx.serialization.Serializable
data class NecesidadMasPreguntasResponse(
    val necesita: Boolean,
    val urgente: Boolean,
    val porcentajeVisto: Int,
    val preguntasRestantes: Int,
    val mensaje: String
)
@kotlinx.serialization.Serializable
data class EstadisticasTema(
    val temaId: String,
    val preguntasActuales: Int,
    val preguntasRequeridas: Int,
    val estudiantesInscritos: Int,
    val cumpleMinimo: Boolean,
    val estadoPublicacion: String
)
