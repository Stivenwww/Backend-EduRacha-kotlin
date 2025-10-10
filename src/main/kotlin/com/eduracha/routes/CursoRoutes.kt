package com.eduracha.routes

import com.eduracha.models.Curso
import com.eduracha.repository.CursoRepository
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.cursoRoutes() {
    val repo = CursoRepository()

    routing {
        route("/api/cursos") {

            post {
                val curso = call.receive<Curso>()
                val id = repo.crearCurso(curso)
                call.respond(mapOf("message" to "Curso creado exitosamente", "id" to id))
            }

            get {
                val cursos = repo.obtenerCursos()
                call.respond(cursos)
            }

            get("{id}") {
                val id = call.parameters["id"] ?: return@get call.respondText("Falta ID", status = io.ktor.http.HttpStatusCode.BadRequest)
                val curso = repo.obtenerCursoPorId(id)
                if (curso == null) {
                    call.respondText("Curso no encontrado", status = io.ktor.http.HttpStatusCode.NotFound)
                } else {
                    call.respond(curso)
                }
            }

            put("{id}") {
                val id = call.parameters["id"] ?: return@put call.respondText("Falta ID", status = io.ktor.http.HttpStatusCode.BadRequest)
                val curso = call.receive<Curso>()
                repo.actualizarCurso(id, curso)
                call.respond(mapOf("message" to "Curso actualizado correctamente"))
            }

            delete("{id}") {
                val id = call.parameters["id"] ?: return@delete call.respondText("Falta ID", status = io.ktor.http.HttpStatusCode.BadRequest)
                repo.eliminarCurso(id)
                call.respond(mapOf("message" to "Curso eliminado"))
            }
        }
    }
}