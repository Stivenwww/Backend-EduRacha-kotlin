package com.eduracha.services

import com.eduracha.models.Curso
import com.eduracha.models.Pregunta
import com.eduracha.models.SolicitudCurso
import com.eduracha.repository.CursoRepository
import com.eduracha.repository.PreguntaRepository
import com.eduracha.repository.SolicitudCursoRepository
import kotlinx.serialization.Serializable

@Serializable
data class PendientesCurso(
    val cursoId: String,
    val titulo: String,
    val codigo: String,
    val preguntas: Int,
    val explicaciones: Int,
    val temasSinExplicacion: Int,
    val aspirantes: Int
) {
    val total: Int get() = preguntas + explicaciones + temasSinExplicacion + aspirantes
}

@Serializable
data class PendientesDocenteResponse(
    val preguntas: Int,
    val explicaciones: Int,
    val temasSinExplicacion: Int,
    val aspirantes: Int,
    val total: Int,
    val cursos: List<PendientesCurso>
)

/**
 * Todo lo que el docente tiene por hacer, agrupado por tipo y por curso:
 * preguntas por validar, explicaciones por revisar, temas sin explicación y aspirantes.
 * [calcular] es una función pura para poder probarla sin Firebase.
 */
object CalculoPendientes {
    fun calcular(
        docenteId: String,
        cursos: List<Curso>,
        preguntasPendientes: List<Pregunta>,
        solicitudesPendientes: List<SolicitudCurso>
    ): PendientesDocenteResponse {
        val mios = cursos.filter { it.docenteId == docenteId && !it.id.isNullOrBlank() }
        val porCurso = mios.map { c ->
            val temas = c.temas?.values.orEmpty()
            PendientesCurso(
                cursoId = c.id!!,
                titulo = c.titulo,
                codigo = c.codigo,
                preguntas = preguntasPendientes.count { it.cursoId == c.id },
                explicaciones = temas.count { !it.explicacion.isNullOrBlank() && it.explicacionEstado != "aprobada" },
                temasSinExplicacion = temas.count { it.explicacion.isNullOrBlank() },
                aspirantes = solicitudesPendientes.count { it.cursoId == c.id }
            )
        }.filter { it.total > 0 }.sortedByDescending { it.total }
        val p = porCurso.sumOf { it.preguntas }
        val e = porCurso.sumOf { it.explicaciones }
        val t = porCurso.sumOf { it.temasSinExplicacion }
        val a = porCurso.sumOf { it.aspirantes }
        return PendientesDocenteResponse(p, e, t, a, p + e + t + a, porCurso)
    }
}

class ServicioPendientes(
    private val cursos: CursoRepository = CursoRepository(),
    private val preguntas: PreguntaRepository = PreguntaRepository(),
    private val solicitudes: SolicitudCursoRepository = SolicitudCursoRepository()
) {
    suspend fun obtener(docenteId: String): PendientesDocenteResponse {
        val mios = cursos.obtenerCursos().filter { it.docenteId == docenteId }
        val preguntasPend = mios.mapNotNull { it.id }.flatMap { id ->
            runCatching { preguntas.obtenerPreguntasPorCursoYEstado(id, "pendiente_revision") }.getOrDefault(emptyList())
        }
        val sol = runCatching { solicitudes.obtenerSolicitudesPendientesPorDocente(docenteId) }.getOrDefault(emptyList())
        return CalculoPendientes.calcular(docenteId, mios, preguntasPend, sol)
    }
}
