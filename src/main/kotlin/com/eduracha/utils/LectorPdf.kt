package com.eduracha.utils

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import kotlinx.serialization.Serializable

/** Resultado de leer un PDF. Si [error] no es null, el documento NO se pudo usar. */
@Serializable
data class ResultadoPdf(
    val nombre: String = "",
    val paginas: Int = 0,
    val caracteres: Int = 0,
    val texto: String = "",
    val escaneado: Boolean = false,
    val truncado: Boolean = false,
    val error: String? = null
)

/**
 * Extracción real de texto de PDF con Apache PDFBox.
 * - Valida tamaño y firma "%PDF".
 * - Detecta PDF dañado, protegido con contraseña o vacío.
 * - Detecta PDF escaneado (solo imágenes): casi sin texto por página.
 *   El OCR no está disponible en este servidor (requiere Tesseract instalado), así que se informa al docente.
 */
object LectorPdf {
    const val TAMANO_MAXIMO_BYTES = 20L * 1024 * 1024   // 20 MB
    const val PAGINAS_MAXIMAS = 300
    const val TEXTO_MAXIMO = 120_000                     // caracteres que se conservan
    private const val MIN_CARACTERES_POR_PAGINA = 40

    fun leer(bytes: ByteArray, nombre: String = "documento.pdf"): ResultadoPdf {
        if (bytes.isEmpty()) return ResultadoPdf(nombre, error = "El archivo está vacío.")
        if (bytes.size > TAMANO_MAXIMO_BYTES) {
            return ResultadoPdf(nombre, error = "El PDF supera el tamaño máximo de 20 MB.")
        }
        if (!esPdf(bytes)) return ResultadoPdf(nombre, error = "El archivo no es un PDF válido.")

        val documento = try {
            PDDocument.load(bytes)
        } catch (e: InvalidPasswordException) {
            return ResultadoPdf(nombre, error = "El PDF está protegido con contraseña. Quita la protección y vuelve a subirlo.")
        } catch (e: Exception) {
            return ResultadoPdf(nombre, error = "No se pudo abrir el PDF: parece dañado o incompleto.")
        }

        documento.use { doc ->
            val paginas = doc.numberOfPages
            if (paginas == 0) return ResultadoPdf(nombre, error = "El PDF no tiene páginas.")
            val hasta = minOf(paginas, PAGINAS_MAXIMAS)
            val crudo = try {
                PDFTextStripper().apply {
                    setSortByPosition(true)
                    setStartPage(1)
                    setEndPage(hasta)
                }.getText(doc)
            } catch (e: Exception) {
                return ResultadoPdf(nombre, paginas, error = "No se pudo extraer el texto del PDF.")
            }
            val texto = normalizar(crudo)
            if (texto.length < MIN_CARACTERES_POR_PAGINA * hasta.coerceAtMost(3)) {
                return ResultadoPdf(
                    nombre, paginas, texto.length, escaneado = true,
                    error = "El PDF no tiene texto seleccionable (parece escaneado o son imágenes). " +
                        "Usa un PDF con texto o copia el contenido en el tema."
                )
            }
            val truncado = texto.length > TEXTO_MAXIMO || paginas > PAGINAS_MAXIMAS
            return ResultadoPdf(
                nombre = nombre,
                paginas = paginas,
                caracteres = texto.length,
                texto = texto.take(TEXTO_MAXIMO),
                truncado = truncado
            )
        }
    }

    fun esPdf(bytes: ByteArray): Boolean {
        // La firma puede venir tras unos pocos bytes basura; se busca en el primer KB
        val cabecera = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
        return cabecera.contains("%PDF-")
    }

    /** Une líneas cortadas, quita guiones de corte y espacios repetidos; conserva los párrafos. */
    fun normalizar(texto: String): String =
        texto.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("-\n(?=\\p{Ll})"), "")
            .replace(Regex("[ \\t\\x0B\\f]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /**
     * Divide un texto largo en fragmentos de hasta [tamano] caracteres respetando los párrafos,
     * para que la IA trabaje sobre todo el documento y no solo sobre el inicio.
     */
    fun fragmentar(texto: String, tamano: Int = 3500): List<String> {
        val parrafos = texto.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val fragmentos = mutableListOf<String>()
        val actual = StringBuilder()
        for (p in parrafos) {
            if (p.length > tamano) {
                if (actual.isNotEmpty()) { fragmentos += actual.toString(); actual.clear() }
                p.chunked(tamano).forEach { fragmentos += it }
                continue
            }
            if (actual.length + p.length + 2 > tamano && actual.isNotEmpty()) {
                fragmentos += actual.toString(); actual.clear()
            }
            if (actual.isNotEmpty()) actual.append("\n\n")
            actual.append(p)
        }
        if (actual.isNotEmpty()) fragmentos += actual.toString()
        return fragmentos
    }
}
