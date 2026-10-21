package com.eduracha.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Generación en lotes (hasta 30 preguntas), reintentos ante límites del proveedor y explicaciones. */
class LotesYLimitesTest {

    /** Proveedor falso: cada llamada devuelve [porLlamada] preguntas nuevas numeradas. */
    private class ProveedorNumerado(private val porLlamada: Int = 10) : AiProvider {
        override val nombre = "falso"
        var llamadas = 0
        val pedidos = mutableListOf<String>()
        override suspend fun chat(req: AiChatRequest): AiChatResponse {
            llamadas++
            pedidos += req.userPrompt
            val pedidas = Regex("Genera (\\d+) preguntas").find(req.userPrompt)?.groupValues?.get(1)?.toInt() ?: porLlamada
            val items = (1..pedidas).joinToString(",") { i ->
                val n = (llamadas - 1) * 100 + i
                """{"texto":"Pregunta número $n sobre el tema","opciones":[
                  {"id":1,"texto":"a$n","esCorrecta":true},{"id":2,"texto":"b$n","esCorrecta":false},
                  {"id":3,"texto":"c$n","esCorrecta":false}],"dificultad":"medio","explicacionCorrecta":"Porque sí."}"""
            }
            return AiChatResponse("""{"preguntas":[$items]}""", nombre, "m")
        }
    }

    @Test fun treintaPreguntasEnTresLotesDeDiez() = runBlocking {
        val prov = ProveedorNumerado()
        val avances = mutableListOf<Int>()
        val out = AiService(prov).generarPreguntasEnLotes("tema", 30) { hechas, _ -> avances += hechas }
        assertEquals(30, out.size)
        assertEquals(3, prov.llamadas)
        assertEquals(listOf(10, 20, 30), avances)
        assertEquals(30, out.map { it.texto }.toSet().size)
    }

    @Test fun cadaLotePideNoRepetirLasAnteriores() = runBlocking {
        val prov = ProveedorNumerado()
        AiService(prov).generarPreguntasEnLotes("tema", 15, evitar = listOf("Pregunta ya existente"))
        assertTrue(prov.pedidos[0].contains("Pregunta ya existente"))
        assertTrue(prov.pedidos[1].contains("Pregunta número 1 sobre el tema"))
        assertTrue(prov.pedidos[1].contains("Genera 5 preguntas"))
    }

    @Test fun descartaPreguntasQueYaExistian() = runBlocking {
        val prov = ProveedorNumerado()
        val out = AiService(prov).generarPreguntasEnLotes("tema", 3, evitar = listOf("Pregunta número 1 sobre el tema"))
        assertEquals(3, out.size)
        assertTrue(out.none { it.texto == "Pregunta número 1 sobre el tema" })
    }

    @Test fun rechazaMasDelTope() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { AiService(ProveedorNumerado()).generarPreguntasEnLotes("tema", AiService.MAX_PREGUNTAS + 1) }
        }
    }

    @Test fun leeEsperaDelProveedor() {
        assertEquals(7.5, LimitesIa.esperaSugerida(null, "Rate limit reached. Please try again in 7.5s."))
        assertEquals(62.5, LimitesIa.esperaSugerida(null, "Please try again in 1m2.5s"))
        assertEquals(0.35, LimitesIa.esperaSugerida(null, "try again in 350ms"))
        assertEquals(12.0, LimitesIa.esperaSugerida("12", "try again in 3s"))
        assertEquals(null, LimitesIa.esperaSugerida(null, "otro error"))
    }

    @Test fun reintentaCuandoHayLimiteYLuegoResponde() = runBlocking {
        var llamadas = 0
        val base = object : AiProvider {
            override val nombre = "falso"
            override suspend fun chat(req: AiChatRequest): AiChatResponse {
                llamadas++
                if (llamadas < 3) throw LimiteIaException("429", 2.0)
                return AiChatResponse("ok", nombre, "m")
            }
        }
        val esperas = mutableListOf<Long>()
        val prov = ProveedorConReintentos(base, dormir = { esperas += it })
        assertEquals("ok", prov.chat(AiChatRequest("s", "u")).content)
        assertEquals(3, llamadas)
        assertEquals(listOf(2500L, 2500L), esperas)
    }

    @Test fun seRindeConMensajeClaroSiElLimiteSigue() {
        val base = object : AiProvider {
            override val nombre = "falso"
            override suspend fun chat(req: AiChatRequest): AiChatResponse = throw LimiteIaException("429", 30.0)
        }
        val prov = ProveedorConReintentos(base, dormir = {})
        val e = assertFailsWith<IllegalStateException> { runBlocking { prov.chat(AiChatRequest("s", "u")) } }
        assertTrue(e.message!!.contains("demasiadas solicitudes"))
    }

    @Test fun otrosErroresNoSeReintentan() {
        var llamadas = 0
        val base = object : AiProvider {
            override val nombre = "falso"
            override suspend fun chat(req: AiChatRequest): AiChatResponse { llamadas++; throw IllegalStateException("llave inválida") }
        }
        assertFailsWith<IllegalStateException> { runBlocking { ProveedorConReintentos(base, dormir = {}).chat(AiChatRequest("s", "u")) } }
        assertEquals(1, llamadas)
    }

    @Test fun explicacionDesdeDocumentoUsaSoloElDocumentoYQuitaMarkdown() = runBlocking {
        var pedido = ""
        val prov = object : AiProvider {
            override val nombre = "falso"
            override suspend fun chat(req: AiChatRequest): AiChatResponse {
                pedido = req.userPrompt
                return AiChatResponse("**Introducción:**\n\n" + "La fotosíntesis convierte luz en energía química. ".repeat(10), nombre, "m")
            }
        }
        val doc = (1..40).joinToString("\n\n") { "Párrafo $it del documento sobre fotosíntesis. " + "x".repeat(400) }
        val out = AiService(prov).generarExplicacionDesdeDocumento("Fotosíntesis", doc, "Biología")
        assertTrue(pedido.contains("ÚNICAMENTE"))
        assertTrue(pedido.contains("Párrafo 1 ") && pedido.contains("Párrafo 3"), "debe tomar partes del inicio")
        assertTrue(Regex("Párrafo (3[0-9]|40) ").containsMatchIn(pedido), "debe incluir partes del final del documento")
        assertTrue(!out.contains("**"))
    }

    @Test fun explicacionDemasiadoCortaSeRechaza() {
        val prov = MockAiProvider("Muy corta.")
        assertFailsWith<IllegalArgumentException> { runBlocking { AiService(prov).generarExplicacion("Tema", null) } }
    }
}
