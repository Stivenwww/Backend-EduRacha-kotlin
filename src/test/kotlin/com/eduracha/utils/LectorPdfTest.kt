package com.eduracha.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LectorPdfTest {

    @Test fun rechazaArchivoVacio() {
        val r = LectorPdf.leer(ByteArray(0), "vacio.pdf")
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("vacío"))
    }

    @Test fun rechazaArchivoQueNoEsPdf() {
        val r = LectorPdf.leer("hola, esto es texto".toByteArray(), "notas.pdf")
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("no es un PDF"))
    }

    @Test fun reconoceFirmaPdf() {
        assertTrue(LectorPdf.esPdf("%PDF-1.7\n...".toByteArray()))
        assertFalse(LectorPdf.esPdf("PK\u0003\u0004".toByteArray()))
    }

    @Test fun normalizaTextoExtraido() {
        val crudo = "Los  algo-\nritmos   son\r\npasos.\n\n\n\nSegundo   párrafo."
        assertEquals("Los algoritmos son\npasos.\n\nSegundo párrafo.", LectorPdf.normalizar(crudo))
    }

    @Test fun fragmentaRespetandoParrafosYTamano() {
        val parrafos = (1..20).joinToString("\n\n") { "Párrafo $it " + "x".repeat(400) }
        val partes = LectorPdf.fragmentar(parrafos, 1000)
        assertTrue(partes.size >= 8)
        assertTrue(partes.all { it.length <= 1000 })
        // No se pierde contenido
        assertEquals(20, partes.joinToString("\n\n").split("Párrafo ").size - 1)
    }

    @Test fun fragmentaParrafoGigante() {
        val partes = LectorPdf.fragmentar("y".repeat(9000), 3500)
        assertEquals(3, partes.size)
        assertEquals(9000, partes.sumOf { it.length })
    }
}
