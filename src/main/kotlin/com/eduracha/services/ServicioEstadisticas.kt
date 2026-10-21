package com.eduracha.services

import com.eduracha.repository.CursoRepository
import com.eduracha.repository.RachaRepository
import com.eduracha.utils.lecturaFirebase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// ===================== Respuesta =====================

@Serializable
data class EstadisticasCursoResponse(
    val cursoId: String,
    val cursoTitulo: String,
    val generado: String,
    val resumen: ResumenEstadisticas,
    val actividadDiaria: List<PuntoActividad>,
    val distribucionPuntajes: List<RangoPuntaje>,
    val porTema: List<EstadisticaTema>,
    val estudiantes: List<EstadisticaEstudiante>,
    val preguntasDificiles: List<PreguntaDificil>
)

@Serializable
data class ResumenEstadisticas(
    val totalEstudiantes: Int,
    val activosUltimos7Dias: Int,
    val quizzesFinalizados: Int,
    /** Porcentaje promedio de aciertos (0-100) en quizzes finalizados. */
    val promedioAciertos: Int,
    /** Porcentaje de quizzes finalizados con 80% o más. */
    val tasaAprobacion: Int,
    val xpTotal: Int,
    val rachaPromedio: Double,
    val tiempoPromedioSeg: Int,
    val estudiantesEnRiesgo: Int
)

@Serializable
data class PuntoActividad(val fecha: String, val quizzes: Int, val estudiantes: Int)

@Serializable
data class RangoPuntaje(val etiqueta: String, val cantidad: Int)

@Serializable
data class EstadisticaTema(
    val temaId: String,
    val titulo: String,
    val intentos: Int,
    val estudiantes: Int,
    val promedio: Int,
    val tasaAprobacion: Int
)

@Serializable
data class EstadisticaEstudiante(
    val estudianteId: String,
    val nombre: String,
    val experiencia: Int,
    val racha: Int,
    val vidas: Int,
    val quizzes: Int,
    val promedio: Int,
    val temasAprobados: Int,
    val ultimaActividad: String? = null,
    val diasSinActividad: Int? = null,
    val enRiesgo: Boolean = false,
    val motivoRiesgo: String? = null
)

@Serializable
data class PreguntaDificil(val preguntaId: String, val texto: String, val respuestas: Int, val aciertos: Int)

// ===================== Datos de entrada =====================

/** Un quiz leído de Firebase con solo lo necesario para las estadísticas. */
data class QuizResumen(
    val estudianteId: String,
    val temaId: String,
    val inicio: String,
    val estado: String,
    val modo: String,
    val correctas: Int,
    val incorrectas: Int,
    val tiempoSeg: Int,
    val experiencia: Int,
    /** preguntaId -> acertó */
    val respuestas: List<Pair<String, Boolean>> = emptyList()
) {
    val porcentaje: Int get() = if (correctas + incorrectas == 0) 0 else (correctas * 100.0 / (correctas + incorrectas)).roundToInt()
}

data class EstudianteBase(val id: String, val nombre: String, val experiencia: Int, val racha: Int, val vidas: Int)

/**
 * Estadísticas del curso para el docente (pantalla de Ranking → Estadísticas).
 * El cálculo es una función pura ([calcular]) para poder probarlo sin Firebase.
 */
object CalculoEstadisticas {
    const val UMBRAL_APROBACION = 80
    private val zona: ZoneId = ZoneId.of("America/Bogota")

    fun fecha(iso: String): LocalDate? = runCatching { Instant.parse(iso).atZone(zona).toLocalDate() }.getOrNull()

    fun calcular(
        cursoId: String,
        cursoTitulo: String,
        temas: Map<String, String>,
        estudiantes: List<EstudianteBase>,
        quizzes: List<QuizResumen>,
        textosPreguntas: Map<String, String> = emptyMap(),
        hoy: LocalDate = LocalDate.now(zona),
        dias: Int = 14
    ): EstadisticasCursoResponse {
        val ids = estudiantes.map { it.id }.toSet()
        val finalizados = quizzes.filter { it.estado == "finalizado" && it.estudianteId in ids }

        // Por estudiante
        val porEstudiante = finalizados.groupBy { it.estudianteId }
        val filas = estudiantes.map { e ->
            val qs = porEstudiante[e.id].orEmpty()
            val ultima = qs.mapNotNull { fecha(it.inicio) }.maxOrNull()
            val diasSin = ultima?.let { java.time.temporal.ChronoUnit.DAYS.between(it, hoy).toInt() }
            val promedio = if (qs.isEmpty()) 0 else qs.map { it.porcentaje }.average().roundToInt()
            val aprobados = qs.filter { it.porcentaje >= UMBRAL_APROBACION && it.modo != "practica" }.map { it.temaId }.toSet().size
            val motivo = when {
                qs.isEmpty() -> "Aún no presenta quizzes"
                diasSin != null && diasSin >= 7 -> "Lleva $diasSin días sin actividad"
                qs.size >= 2 && promedio < 60 -> "Promedio de aciertos bajo ($promedio%)"
                e.vidas == 0 -> "Se quedó sin vidas"
                else -> null
            }
            EstadisticaEstudiante(
                estudianteId = e.id,
                nombre = e.nombre,
                experiencia = e.experiencia,
                racha = e.racha,
                vidas = e.vidas,
                quizzes = qs.size,
                promedio = promedio,
                temasAprobados = aprobados,
                ultimaActividad = ultima?.toString(),
                diasSinActividad = diasSin,
                enRiesgo = motivo != null,
                motivoRiesgo = motivo
            )
        }.sortedWith(compareByDescending<EstadisticaEstudiante> { it.experiencia }.thenByDescending { it.promedio })

        // Actividad de los últimos [dias] días
        val desde = hoy.minusDays((dias - 1).toLong())
        val porDia = finalizados.mapNotNull { q -> fecha(q.inicio)?.let { it to q } }.filter { !it.first.isBefore(desde) }.groupBy({ it.first }, { it.second })
        val actividad = (0 until dias).map { i ->
            val d = desde.plusDays(i.toLong())
            val qs = porDia[d].orEmpty()
            PuntoActividad(d.toString(), qs.size, qs.map { it.estudianteId }.toSet().size)
        }

        // Distribución de puntajes
        val rangos = listOf(0 to 19, 20 to 39, 40 to 59, 60 to 79, 80 to 100)
        val distribucion = rangos.map { (a, b) ->
            RangoPuntaje("$a-$b%", finalizados.count { it.porcentaje in a..b })
        }

        // Por tema (en el orden del curso)
        val porTemaQ = finalizados.filter { it.modo != "final" }.groupBy { it.temaId }
        val porTema = temas.map { (temaId, titulo) ->
            val qs = porTemaQ[temaId].orEmpty()
            EstadisticaTema(
                temaId = temaId,
                titulo = titulo,
                intentos = qs.size,
                estudiantes = qs.map { it.estudianteId }.toSet().size,
                promedio = if (qs.isEmpty()) 0 else qs.map { it.porcentaje }.average().roundToInt(),
                tasaAprobacion = if (qs.isEmpty()) 0 else (qs.count { it.porcentaje >= UMBRAL_APROBACION } * 100.0 / qs.size).roundToInt()
            )
        }

        // Preguntas con menos aciertos (mínimo 3 respuestas)
        val dificiles = finalizados.flatMap { it.respuestas }.groupBy({ it.first }, { it.second })
            .filter { it.value.size >= 3 }
            .map { (id, rs) -> PreguntaDificil(id, textosPreguntas[id] ?: "Pregunta $id", rs.size, rs.count { it }) }
            .sortedBy { it.aciertos.toDouble() / it.respuestas }
            .take(5)

        val activos7 = finalizados.filter { q -> fecha(q.inicio)?.let { !it.isBefore(hoy.minusDays(6)) } == true }
            .map { it.estudianteId }.toSet().size
        val resumen = ResumenEstadisticas(
            totalEstudiantes = estudiantes.size,
            activosUltimos7Dias = activos7,
            quizzesFinalizados = finalizados.size,
            promedioAciertos = if (finalizados.isEmpty()) 0 else finalizados.map { it.porcentaje }.average().roundToInt(),
            tasaAprobacion = if (finalizados.isEmpty()) 0 else (finalizados.count { it.porcentaje >= UMBRAL_APROBACION } * 100.0 / finalizados.size).roundToInt(),
            xpTotal = estudiantes.sumOf { it.experiencia },
            rachaPromedio = if (estudiantes.isEmpty()) 0.0 else Math.round(estudiantes.map { it.racha }.average() * 10) / 10.0,
            tiempoPromedioSeg = if (finalizados.isEmpty()) 0 else finalizados.map { it.tiempoSeg }.average().roundToInt(),
            estudiantesEnRiesgo = filas.count { it.enRiesgo }
        )
        return EstadisticasCursoResponse(
            cursoId = cursoId,
            cursoTitulo = cursoTitulo,
            generado = Instant.now().toString(),
            resumen = resumen,
            actividadDiaria = actividad,
            distribucionPuntajes = distribucion,
            porTema = porTema,
            estudiantes = filas,
            preguntasDificiles = dificiles
        )
    }
}

/** Lee de Firebase lo necesario y calcula las estadísticas. */
class ServicioEstadisticas(
    private val cursoRepo: CursoRepository = CursoRepository(),
    private val rachaRepo: RachaRepository = RachaRepository()
) {
    private val db = FirebaseDatabase.getInstance()

    private suspend fun leer(ref: com.google.firebase.database.Query): DataSnapshot? = try {
        lecturaFirebase { cont ->
            ref.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) { cont.resume(snapshot) }
                override fun onCancelled(error: DatabaseError) { cont.resume(null) }
            })
        }
    } catch (e: Exception) { null }

    private fun entero(v: Any?): Int = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    suspend fun obtener(cursoId: String): EstadisticasCursoResponse {
        val curso = cursoRepo.obtenerCursoPorId(cursoId) ?: throw IllegalStateException("Curso no encontrado")
        val temas = (curso.temas ?: emptyMap()).entries
            .sortedBy { it.value.fechaCreacion }
            .associate { (id, t) -> (t.id ?: id) to t.titulo }
        val estudiantes = rachaRepo.obtenerRankingPorCurso(cursoId, "experiencia").map {
            EstudianteBase(it.estudianteId, it.nombre ?: "Estudiante", it.experiencia, it.rachaDias, it.vidas)
        }
        val snap = leer(db.getReference("quizzes").orderByChild("cursoId").equalTo(cursoId))
        val quizzes = snap?.children?.mapNotNull { q ->
            runCatching {
                QuizResumen(
                    estudianteId = q.child("estudianteId").value as? String ?: return@runCatching null,
                    temaId = q.child("temaId").value as? String ?: "",
                    inicio = q.child("inicio").value as? String ?: "",
                    estado = q.child("estado").value as? String ?: "",
                    modo = q.child("modo").value as? String ?: "oficial",
                    correctas = entero(q.child("preguntasCorrectas").value),
                    incorrectas = entero(q.child("preguntasIncorrectas").value),
                    tiempoSeg = entero(q.child("tiempoUsadoSeg").value),
                    experiencia = entero(q.child("experienciaGanada").value),
                    respuestas = q.child("respuestas").children.mapNotNull { r ->
                        val id = r.child("preguntaId").value as? String ?: return@mapNotNull null
                        id to (r.child("esCorrecta").value as? Boolean ?: false)
                    }
                )
            }.getOrNull()
        }.orEmpty()

        // Textos de las preguntas con más respuestas (para mostrar las más difíciles)
        val candidatas = quizzes.flatMap { it.respuestas }.groupingBy { it.first }.eachCount()
            .filter { it.value >= 3 }.keys.take(60)
        val textos = candidatas.mapNotNull { id ->
            leer(db.getReference("preguntas").child(id).child("texto"))?.value?.let { id to it.toString() }
        }.toMap()

        return CalculoEstadisticas.calcular(cursoId, curso.titulo, temas, estudiantes, quizzes, textos)
    }
}
