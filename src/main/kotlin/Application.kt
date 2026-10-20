package com.eduracha

import com.eduracha.routes.*
import com.eduracha.utils.FirebaseInit
import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import com.eduracha.repository.*
import com.eduracha.services.*
import com.eduracha.jobs.CronJobReportes
import com.eduracha.utils.getUserFromToken
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.eduracha.utils.JwtConfig
import io.ktor.server.plugins.cors.routing.*
import com.google.auth.oauth2.GoogleCredentials
import java.util.Base64

fun main() {
    embeddedServer(
        Netty,
        port = System.getenv("PORT")?.toInt() ?: 8080,
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {

    // Cargar dotenv solo en desarrollo
   /*  val dotenv = try {
        dotenv { ignoreIfMissing = true }
    } catch (e: Exception) {
        
    }*/
    //fun getEnv(key: String): String? =
        //System.getenv(key)

        val dotenv = try {
            dotenv { ignoreIfMissing = true }
        } catch (e: Exception) {
            null
        }

        fun getEnv(key: String): String =
    System.getenv(key)
        ?: dotenv?.get(key)
        ?: throw IllegalStateException("Variable $key no encontrada")


    val firebaseUrl = getEnv("FIREBASE_DATABASE_URL")
        ?: throw IllegalStateException("FIREBASE_DATABASE_URL no encontrada")

    val base64 = try {
        getEnv("FIREBASE_CREDENTIALS_BASE64")
    } catch (_: Exception) { null }

    // Decodificar credenciales Firebase (tolera base64 std/url-safe, JSON crudo o ruta)
    val googleCredentials = com.eduracha.utils.FirebaseCredenciales.cargar(base64)
    println("Firebase URL: $firebaseUrl")
    println("Credenciales Firebase cargadas correctamente")

    // Inicializar Firebase
    FirebaseInit.initialize(googleCredentials, firebaseUrl)

    // Repositorios
    val preguntaRepo = PreguntaRepository()
    val quizRepo = QuizRepository()
    val cursoRepo = CursoRepository()
    val solicitudPreguntasRepo = SolicitudPreguntasRepository()

    // Servicios
    val servicioSeleccion = ServicioSeleccionPreguntas(preguntaRepo, cursoRepo)
    val quizService = QuizService(
        quizRepo = quizRepo,
        preguntaRepo = preguntaRepo,
        cursoRepo = cursoRepo,
        servicioSeleccion = servicioSeleccion,
        solicitudPreguntasRepo = solicitudPreguntasRepo
    )

    val servicioReportes = ServicioReportesExcel(quizRepo, cursoRepo)

    // IA desacoplada: selección por AI_PROVIDER. No rompe si falta clave (opera degradada).
    val aiConfig = try { com.eduracha.ai.AiConfig.desdeEntorno { System.getenv(it) ?: dotenv?.get(it) } }
        catch (_: Exception) { null }
    var errorInicioIA: String? = null
    val aiService: com.eduracha.ai.AiService? = try {
        if (aiConfig != null) { com.eduracha.ai.AiConfig.validar(aiConfig); com.eduracha.ai.AiService(com.eduracha.ai.AiProviderFactory.crear(aiConfig)) }
        else null
    } catch (e: Exception) {
        errorInicioIA = e.message
        println("IA no disponible: ${e.message}")
        null
    }
    if (aiService != null) println("Proveedor IA activo: ${aiService.proveedorActual} | modelo: ${aiConfig?.model} | llave: ...${aiConfig?.apiKey?.takeLast(4)}")
    else println("ADVERTENCIA: IA no configurada. Configure AI_PROVIDER/AI_MODEL/AI_API_KEY.")
    println("Diagnóstico de IA: abre http://localhost:8080/api/ia/diagnostico en el navegador de esta PC")

    install(ContentNegotiation) { json() }

    if (JwtConfig.isDefaultSecret) {
        println("ADVERTENCIA: usando JWT_SECRET por defecto. Configure JWT_SECRET en producción.")
    }

    install(CORS) {
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Put)
        allowMethod(io.ktor.http.HttpMethod.Delete)
        allowHeader(io.ktor.http.HttpHeaders.Authorization)
        allowHeader(io.ktor.http.HttpHeaders.ContentType)
        // Ajuste a dominios reales en producción
        anyHost()
        allowNonSimpleContentTypes = true
    }

    // JWT dummy
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                JWT.require(Algorithm.HMAC256(JwtConfig.jwtSecret())).withIssuer("eduracha-server").build()
            )
            validate { JWTPrincipal(it.payload) }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Token inválido"))
            }
        }
    }

    routing {
        get("/") { call.respondText("Servidor EduRacha corriendo correctamente") }

        authRoutes()
        usuarioRoutes()
        preferenciasRoutes()
        iaDiagnosticoRoutes(aiConfig, aiService, errorInicioIA)
        cursoRoutes()
        solicitudCursoRoutes()
        chatRoutes(aiService)
        preguntasRoutes(aiService)
        quizRoutes(quizService, quizRepo, cursoRepo)
        reportesRoutes(servicioReportes)
    }

    configureCronJobs(servicioReportes, cursoRepo, quizRepo)
}

fun Application.configureCronJobs(
    servicioReportes: ServicioReportesExcel,
    cursoRepo: CursoRepository,
    quizRepo: QuizRepository
) {
    val cronJob = CronJobReportes(servicioReportes, cursoRepo)
    cronJob.iniciar()

    environment.monitor.subscribe(ApplicationStopped) {
        cronJob.detener()
    }

    routing {
        post("/admin/generar-reportes") {
            val user = call.getUserFromToken()
                ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Token inválido o expirado")
                )

            if (user.rol !in listOf("admin", "docente")) {
                return@post call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("error" to "Acceso denegado")
                )
            }

            GlobalScope.launch { cronJob.ejecutarManualmente() }
            call.respond(HttpStatusCode.OK, mapOf("message" to "Generación de reportes iniciada"))
        }
    }
}
