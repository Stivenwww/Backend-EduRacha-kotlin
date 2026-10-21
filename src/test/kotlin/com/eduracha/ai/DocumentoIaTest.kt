package com.eduracha.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Proveedor falso: cada llamada devuelve preguntas distintas y registra cuántas pidió la app. */
private class ProveedorContador : AiProvider {
    override val nombre = "contador"
    val pedidos = mutableListOf<Int>()
    val fragmentos = mutableListOf<String>()
    private var n = 0
    override suspend fun chat(req: AiChatRequest): AiChatResponse {
        val cantidad = Regex("Genera (\\d+) preguntas").find(req.userPrompt)!!.groupValues[1].toInt()
        pedidos += cantidad
        fragmentos += req.userPrompt
        val preguntas = (1..cantidad).joinToString(",") {
            n++
            """{"texto":"Pregunta $n","opciones":[{"id":1,"texto":"a","esCorrecta":true},{"id":2,"texto":"b","esCorrecta":false},{"id":3,"texto":"c","esCorrecta":false}],"dificultad":"medio","explicacionCorrecta":"porque"}"""
        }
        return AiChatResponse("""{"preguntas":[$preguntas]}""", nombre, "m")
    }
}

class DocumentoIaTest {

    private val documentoLargo = (1..30).joinToString("\n\n") { "Sección $it. " + "contenido ".repeat(60) }

    @Test fun repartePreguntasEnTodoElDocumento() = runBlocking {
        val prov = ProveedorContador()
        val out = AiService(prov).generarPreguntasDesdeDocumento(documentoLargo, 10, "dificil")
        assertEquals(10, out.size)
        assertEquals(10, prov.pedidos.sum())
        assertEquals(6, prov.pedidos.size)               // máximo 6 fragmentos
        assertTrue(prov.fragmentos.first().contains("Sección 1."))
        assertTrue(prov.fragmentos.last().contains("Sección 2"))  // llega a la parte final del documento
        assertTrue(prov.fragmentos.all { it.contains("dificil") })
    }

    @Test fun documentoCortoUsaUnSoloFragmento() = runBlocking {
        val prov = ProveedorContador()
        val out = AiService(prov).generarPreguntasDesdeDocumento("Texto breve sobre fracciones.", 4)
        assertEquals(4, out.size)
        assertEquals(listOf(4), prov.pedidos)
    }
}
