package com.eduracha.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class AiServiceTest {

    private val preguntasJson = """
        {"preguntas":[
          {"texto":"¿Cuánto es 2+2?","opciones":[
            {"id":1,"texto":"4","esCorrecta":true},
            {"id":2,"texto":"3","esCorrecta":false},
            {"id":3,"texto":"5","esCorrecta":false}],
           "dificultad":"facil","explicacionCorrecta":"Suma básica."},
          {"texto":"¿Capital de Colombia?","opciones":[
            {"id":1,"texto":"Bogotá","esCorrecta":true},
            {"id":2,"texto":"Medellín","esCorrecta":false},
            {"id":3,"texto":"Cali","esCorrecta":false}],
           "dificultad":"medio","explicacionCorrecta":"Bogotá es la capital."}
        ]}
    """.trimIndent()

    @Test fun seleccionProveedorPorEnv() {
        val cfg = AiConfig("groq", "llama-3.1-8b-instant", "k", null, 60)
        AiConfig.validar(cfg)
        assertEquals("groq", cfg.provider)
    }

    @Test fun proveedorDesconocidoRechazado() {
        assertFailsWith<IllegalArgumentException> {
            AiConfig.validar(AiConfig("inexistente", "m", "k", null, 60))
        }
    }

    @Test fun faltaClaveRechazada() {
        assertFailsWith<IllegalArgumentException> {
            AiConfig.validar(AiConfig("groq", "m", "", null, 60))
        }
    }

    @Test fun mockValidaPreguntas() = runBlocking {
        val svc = AiService(MockAiProvider(preguntasJson))
        val out = svc.generarPreguntasJson("matemáticas", 2)
        assertEquals(2, out.size)
        assertEquals("facil", out[0].dificultad)
        assertTrue(out.all { it.opciones.size == 3 && it.opciones.count { o -> o.esCorrecta } == 1 })
    }

    @Test fun mockRechazaJsonInvalido() = runBlocking {
        val svc = AiService(MockAiProvider("no es json"))
        try {
            svc.generarPreguntasJson("tema", 1)
            assertFalse(true, "debió fallar")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test fun mockRechazaSinCorrecta() = runBlocking {
        val malo = """{"preguntas":[{"texto":"X","opciones":[
          {"id":1,"texto":"a","esCorrecta":false},
          {"id":2,"texto":"b","esCorrecta":false},
          {"id":3,"texto":"c","esCorrecta":false}],"dificultad":"medio"}]}"""
        val svc = AiService(MockAiProvider(malo))
        try {
            svc.generarPreguntasJson("tema", 1)
            assertFalse(true, "debió fallar")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("correcta"))
        }
    }
}
