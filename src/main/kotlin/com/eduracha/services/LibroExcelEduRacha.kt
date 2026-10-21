package com.eduracha.services

import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.*
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Libro de Excel con la identidad visual de EduRacha (paleta Nexo):
 *  - franja de marca, título del reporte y datos del curso;
 *  - tarjetas de indicadores;
 *  - tabla con encabezado de color, filas alternas, formatos numéricos, filtros y panel fijo;
 *  - estados coloreados (Aprobado, En progreso, Sin actividad);
 *  - gráfico de barras opcional y pie de página.
 * Todos los reportes usan este mismo diseño, así el archivo descargado y la vista previa de la app coinciden.
 */
object LibroExcelEduRacha {

    enum class TipoCelda { TEXTO, ENTERO, DECIMAL, PORCENTAJE, ESTADO }

    data class Columna(val titulo: String, val tipo: TipoCelda = TipoCelda.TEXTO, val ancho: Int = 16)
    data class Indicador(val etiqueta: String, val valor: String)
    /** Gráfico de barras: una barra por fila, usando la columna [columnaEtiqueta] y la columna numérica [columnaValor]. */
    data class Grafico(val titulo: String, val columnaEtiqueta: Int, val columnaValor: Int, val tituloEje: String)

    data class Reporte(
        val titulo: String,
        val curso: String,
        val detalle: String,
        val indicadores: List<Indicador>,
        val tituloTabla: String,
        val columnas: List<Columna>,
        val filas: List<List<Any?>>,
        val grafico: Grafico? = null,
        val notaVacia: String = "Todavía no hay actividad registrada para este reporte."
    )

    // Paleta Nexo (modo claro)
    private val MARCA_OSCURA = rgb(0x5B, 0x3F, 0xE0)   // violeta IA (franja de marca)
    private val MARCA = rgb(0x0B, 0x8F, 0xA1)          // cian profundo (título)
    private val ENCABEZADO = rgb(0x08, 0x7C, 0x8C)     // encabezado de tabla
    private val FONDO_SUAVE = rgb(0xF2, 0xFB, 0xFF)    // filas alternas
    private val BORDE = rgb(0xE1, 0xEA, 0xF2)
    private val TEXTO = rgb(0x23, 0x30, 0x42)
    private val TEXTO_SUAVE = rgb(0x6B, 0x7A, 0x90)
    private val VERDE_FONDO = rgb(0xDC, 0xF7, 0xE5)
    private val VERDE_TEXTO = rgb(0x1E, 0x7D, 0x3D)
    private val AMBAR_FONDO = rgb(0xFF, 0xF1, 0xD6)
    private val AMBAR_TEXTO = rgb(0xB3, 0x5F, 0x00)
    private val GRIS_FONDO = rgb(0xEE, 0xF1, 0xF5)
    private val ROJO_FONDO = rgb(0xFF, 0xE3, 0xE7)
    private val ROJO_TEXTO = rgb(0xC4, 0x2A, 0x3F)

    private fun rgb(r: Int, g: Int, b: Int) = XSSFColor(byteArrayOf(r.toByte(), g.toByte(), b.toByte()), null)

    fun crear(reporte: Reporte): ByteArray {
        val libro = XSSFWorkbook()
        val est = Estilos(libro)
        val hoja = libro.createSheet(nombreHoja(reporte.titulo))
        val nCols = maxOf(reporte.columnas.size, 6)
        var fila = 0

        // 1. Franja de marca
        hoja.createRow(fila).apply { heightInPoints = 34f }
        celdaCombinada(hoja, fila, 0, nCols - 1, "EduRacha  ·  Aprende en racha", est.marca)
        fila++
        // 2. Título del reporte
        hoja.createRow(fila).apply { heightInPoints = 26f }
        celdaCombinada(hoja, fila, 0, nCols - 1, reporte.titulo, est.titulo)
        fila++
        // 3. Curso y detalle
        val generado = LocalDateTime.now(ZoneId.of("America/Bogota")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        hoja.createRow(fila).apply { heightInPoints = 20f }
        celdaCombinada(hoja, fila, 0, nCols - 1, "Curso: ${reporte.curso}   ·   ${reporte.detalle}   ·   Generado: $generado", est.subtitulo)
        fila += 2

        // 4. Indicadores: cada uno ocupa 2 columnas (etiqueta arriba, valor abajo)
        if (reporte.indicadores.isNotEmpty()) {
            val filaEtiqueta = hoja.createRow(fila).apply { heightInPoints = 18f }
            val filaValor = hoja.createRow(fila + 1).apply { heightInPoints = 30f }
            reporte.indicadores.take(nCols / 2).forEachIndexed { i, ind ->
                val c = i * 2
                filaEtiqueta.createCell(c).apply { setCellValue(ind.etiqueta.uppercase()); cellStyle = est.kpiEtiqueta }
                filaEtiqueta.createCell(c + 1).cellStyle = est.kpiEtiqueta
                filaValor.createCell(c).apply { setCellValue(ind.valor); cellStyle = est.kpiValor }
                filaValor.createCell(c + 1).cellStyle = est.kpiValor
                hoja.addMergedRegion(CellRangeAddress(fila, fila, c, c + 1))
                hoja.addMergedRegion(CellRangeAddress(fila + 1, fila + 1, c, c + 1))
            }
            fila += 3
        }

        // 5. Tabla
        hoja.createRow(fila).apply { heightInPoints = 22f }
        celdaCombinada(hoja, fila, 0, nCols - 1, reporte.tituloTabla, est.tituloTabla)
        fila++
        val filaEncabezado = fila
        hoja.createRow(fila).apply {
            heightInPoints = 30f
            reporte.columnas.forEachIndexed { i, col -> createCell(i).apply { setCellValue(col.titulo); cellStyle = est.encabezado } }
        }
        fila++
        if (reporte.filas.isEmpty()) {
            hoja.createRow(fila).apply { heightInPoints = 24f }
            celdaCombinada(hoja, fila, 0, reporte.columnas.size - 1, reporte.notaVacia, est.vacio)
            fila++
        }
        reporte.filas.forEachIndexed { idx, valores ->
            val alterna = idx % 2 == 1
            val r = hoja.createRow(fila).apply { heightInPoints = 20f }
            reporte.columnas.forEachIndexed { i, col ->
                val celda = r.createCell(i)
                val v = valores.getOrNull(i)
                when (col.tipo) {
                    TipoCelda.TEXTO -> { celda.setCellValue(v?.toString() ?: ""); celda.cellStyle = est.texto(alterna) }
                    TipoCelda.ENTERO -> { celda.setCellValue(numero(v)); celda.cellStyle = est.entero(alterna) }
                    TipoCelda.DECIMAL -> { celda.setCellValue(numero(v)); celda.cellStyle = est.decimal(alterna) }
                    TipoCelda.PORCENTAJE -> { celda.setCellValue(numero(v) / 100.0); celda.cellStyle = est.porcentaje(alterna) }
                    TipoCelda.ESTADO -> { val t = v?.toString() ?: ""; celda.setCellValue(t); celda.cellStyle = est.estado(t) }
                }
            }
            fila++
        }
        val ultimaFilaDatos = fila - 1
        if (reporte.filas.isNotEmpty()) {
            hoja.setAutoFilter(CellRangeAddress(filaEncabezado, ultimaFilaDatos, 0, reporte.columnas.size - 1))
        }
        hoja.createFreezePane(0, filaEncabezado + 1)

        // Anchos de columna
        reporte.columnas.forEachIndexed { i, col ->
            val maxTexto = reporte.filas.maxOfOrNull { (it.getOrNull(i)?.toString() ?: "").length } ?: 0
            val ancho = maxOf(col.ancho, minOf(maxTexto + 3, 48), col.titulo.length + 2)
            hoja.setColumnWidth(i, ancho * 256)
        }
        for (i in reporte.columnas.size until nCols) hoja.setColumnWidth(i, 14 * 256)

        // 6. Pie
        fila++
        hoja.createRow(fila)
        celdaCombinada(hoja, fila, 0, nCols - 1, "Generado automáticamente por EduRacha · Corporación Universitaria Autónoma del Cauca", est.pie)

        // 7. Gráfico
        val g = reporte.grafico
        if (g != null && reporte.filas.isNotEmpty()) {
            runCatching { agregarGrafico(hoja, g, filaEncabezado + 1, ultimaFilaDatos, reporte.columnas.size + 1) }
                .onFailure { println("Excel: no se pudo agregar el gráfico (${it.message})") }
        }

        // Impresión: horizontal y ajustado al ancho de la página
        hoja.printSetup.landscape = true
        hoja.fitToPage = true
        hoja.printSetup.fitWidth = 1
        hoja.printSetup.fitHeight = 0
        hoja.isDisplayGridlines = false

        val salida = ByteArrayOutputStream()
        libro.write(salida)
        libro.close()
        return salida.toByteArray()
    }

    private fun agregarGrafico(hoja: XSSFSheet, g: Grafico, desde: Int, hasta: Int, columnaInicio: Int) {
        val dibujo = hoja.createDrawingPatriarch()
        val ancla = dibujo.createAnchor(0, 0, 0, 0, columnaInicio, 5, columnaInicio + 8, 5 + 20)
        val grafico = dibujo.createChart(ancla)
        grafico.setTitleText(g.titulo)
        grafico.titleOverlay = false
        val ejeX = grafico.createCategoryAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.BOTTOM)
        val ejeY = grafico.createValueAxis(org.apache.poi.xddf.usermodel.chart.AxisPosition.LEFT)
        ejeY.setTitle(g.tituloEje)
        ejeY.crossBetween = org.apache.poi.xddf.usermodel.chart.AxisCrossBetween.BETWEEN
        val categorias = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromStringCellRange(
            hoja, CellRangeAddress(desde, hasta, g.columnaEtiqueta, g.columnaEtiqueta)
        )
        val valores = org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory.fromNumericCellRange(
            hoja, CellRangeAddress(desde, hasta, g.columnaValor, g.columnaValor)
        )
        val datos = grafico.createData(org.apache.poi.xddf.usermodel.chart.ChartTypes.BAR, ejeX, ejeY)
            as org.apache.poi.xddf.usermodel.chart.XDDFBarChartData
        datos.barDirection = org.apache.poi.xddf.usermodel.chart.BarDirection.COL
        datos.setVaryColors(false)
        val serie = datos.addSeries(categorias, valores)
        serie.setTitle(g.tituloEje, null)
        grafico.plot(datos)
        // Barras con el color de la marca
        val relleno = org.apache.poi.xddf.usermodel.XDDFSolidFillProperties(
            org.apache.poi.xddf.usermodel.XDDFColor.from(byteArrayOf(0x12, 0xB5.toByte(), 0xCB.toByte()))
        )
        val propiedades = serie.shapeProperties ?: org.apache.poi.xddf.usermodel.XDDFShapeProperties()
        propiedades.fillProperties = relleno
        serie.shapeProperties = propiedades
    }

    private fun numero(v: Any?): Double = when (v) {
        is Number -> v.toDouble().let { if (it.isNaN() || it.isInfinite()) 0.0 else it }
        is String -> v.replace(",", ".").toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun nombreHoja(titulo: String): String =
        titulo.replace(Regex("[\\\\/?*\\[\\]:]"), " ").trim().take(31).ifBlank { "Reporte" }

    private fun celdaCombinada(hoja: XSSFSheet, fila: Int, desde: Int, hasta: Int, texto: String, estilo: XSSFCellStyle) {
        val r = hoja.getRow(fila) ?: hoja.createRow(fila)
        for (c in desde..hasta) (r.getCell(c) ?: r.createCell(c)).cellStyle = estilo
        r.getCell(desde).setCellValue(texto)
        if (hasta > desde) hoja.addMergedRegion(CellRangeAddress(fila, fila, desde, hasta))
    }

    /** Estilos compartidos del libro (POI limita la cantidad de estilos, así que se crean una vez). */
    private class Estilos(private val libro: XSSFWorkbook) {
        private fun fuente(tam: Short, negrita: Boolean, color: XSSFColor, cursiva: Boolean = false) =
            (libro.createFont() as XSSFFont).apply {
                fontName = "Calibri"; fontHeightInPoints = tam; bold = negrita; italic = cursiva; setColor(color)
            }

        private fun base(fondo: XSSFColor?, f: XSSFFont, alin: HorizontalAlignment = HorizontalAlignment.LEFT, borde: Boolean = false) =
            (libro.createCellStyle() as XSSFCellStyle).apply {
                if (fondo != null) { setFillForegroundColor(fondo); fillPattern = FillPatternType.SOLID_FOREGROUND }
                setFont(f)
                alignment = alin
                verticalAlignment = VerticalAlignment.CENTER
                wrapText = false
                if (borde) {
                    borderBottom = BorderStyle.THIN; borderTop = BorderStyle.THIN
                    borderLeft = BorderStyle.THIN; borderRight = BorderStyle.THIN
                    setBottomBorderColor(BORDE); setTopBorderColor(BORDE); setLeftBorderColor(BORDE); setRightBorderColor(BORDE)
                }
            }

        private val blanco = rgb(0xFF, 0xFF, 0xFF)
        val marca = base(MARCA_OSCURA, fuente(18, true, blanco)).apply { indention = 1 }
        val titulo = base(MARCA, fuente(14, true, blanco)).apply { indention = 1 }
        val subtitulo = base(FONDO_SUAVE, fuente(10, false, TEXTO_SUAVE, cursiva = true)).apply { indention = 1 }
        val kpiEtiqueta = base(FONDO_SUAVE, fuente(9, true, TEXTO_SUAVE), HorizontalAlignment.CENTER, borde = true)
        val kpiValor = base(blanco, fuente(18, true, MARCA), HorizontalAlignment.CENTER, borde = true)
        val tituloTabla = base(null, fuente(12, true, TEXTO))
        val encabezado = base(ENCABEZADO, fuente(10, true, blanco), HorizontalAlignment.CENTER, borde = true).apply { wrapText = true }
        val vacio = base(GRIS_FONDO, fuente(10, false, TEXTO_SUAVE, cursiva = true), HorizontalAlignment.CENTER, borde = true)
        val pie = base(null, fuente(9, false, TEXTO_SUAVE, cursiva = true))

        private val fDatos = fuente(10, false, TEXTO)
        private val formato = libro.createDataFormat()
        private val textoN = base(blanco, fDatos, borde = true)
        private val textoA = base(FONDO_SUAVE, fDatos, borde = true)
        private val enteroN = base(blanco, fDatos, HorizontalAlignment.CENTER, true).apply { dataFormat = formato.getFormat("#,##0") }
        private val enteroA = base(FONDO_SUAVE, fDatos, HorizontalAlignment.CENTER, true).apply { dataFormat = formato.getFormat("#,##0") }
        private val decimalN = base(blanco, fDatos, HorizontalAlignment.CENTER, true).apply { dataFormat = formato.getFormat("0.0") }
        private val decimalA = base(FONDO_SUAVE, fDatos, HorizontalAlignment.CENTER, true).apply { dataFormat = formato.getFormat("0.0") }
        private val pctN = base(blanco, fDatos, HorizontalAlignment.CENTER, true).apply { dataFormat = formato.getFormat("0%") }
        private val pctA = base(FONDO_SUAVE, fDatos, HorizontalAlignment.CENTER, true).apply { dataFormat = formato.getFormat("0%") }
        private val aprobado = base(VERDE_FONDO, fuente(10, true, VERDE_TEXTO), HorizontalAlignment.CENTER, true)
        private val progreso = base(AMBAR_FONDO, fuente(10, true, AMBAR_TEXTO), HorizontalAlignment.CENTER, true)
        private val riesgo = base(ROJO_FONDO, fuente(10, true, ROJO_TEXTO), HorizontalAlignment.CENTER, true)
        private val sinDatos = base(GRIS_FONDO, fuente(10, true, TEXTO_SUAVE), HorizontalAlignment.CENTER, true)

        fun texto(a: Boolean) = if (a) textoA else textoN
        fun entero(a: Boolean) = if (a) enteroA else enteroN
        fun decimal(a: Boolean) = if (a) decimalA else decimalN
        fun porcentaje(a: Boolean) = if (a) pctA else pctN
        fun estado(t: String): XSSFCellStyle {
            val m = t.lowercase()
            return when {
                "aprob" in m || "complet" in m || "activo" in m -> aprobado
                "riesgo" in m || "reprob" in m -> riesgo
                "progreso" in m || "pendiente" in m -> progreso
                else -> sinDatos
            }
        }
    }
}
