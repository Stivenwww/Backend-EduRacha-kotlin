package com.eduracha.routes

import com.eduracha.services.redondear1
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportesExcelTest {

    @Test fun cabeceraValidaConAcentos() {
        val h = contentDispositionExcel("reporte_general_Programación_Básica_ñ.xlsx")
        assertTrue(h.startsWith("attachment; filename=\"reporte_general_Programacion_Basica_n.xlsx\""))
        assertTrue(h.contains("filename*=UTF-8''reporte_general_Programaci%C3%B3n_B%C3%A1sica_%C3%B1.xlsx"))
        assertTrue(h.all { it.code < 128 }, "la cabecera HTTP debe ser ASCII")
    }

    @Test fun redondeoNumerico() {
        assertEquals(2.3, redondear1(2.345))
        assertEquals(0.0, redondear1(emptyList<Int>().average()))  // antes salía "NaN"
    }

    @Test fun libroXlsxConAcentosEsValido() {
        val libro = XSSFWorkbook()
        val hoja = libro.createSheet("Reporte")
        hoja.createRow(0).createCell(0).setCellValue("Estudiante: José Ñúñez — Programación")
        hoja.createRow(1).createCell(0).setCellValue(redondear1(7.25))
        val salida = ByteArrayOutputStream()
        libro.write(salida); libro.close()
        val bytes = salida.toByteArray()
        // Firma ZIP de un .xlsx real (la misma que valida la app)
        assertTrue(bytes.size > 100 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte())
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { leido ->
            val h = leido.getSheet("Reporte")
            assertEquals("Estudiante: José Ñúñez — Programación", h.getRow(0).getCell(0).stringCellValue)
            assertEquals(7.3, h.getRow(1).getCell(0).numericCellValue)
        }
    }
}
