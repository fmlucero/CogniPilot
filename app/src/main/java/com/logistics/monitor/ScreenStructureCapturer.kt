package com.logistics.monitor

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Captura la ESTRUCTURA de las pantallas de la app de trabajo (SC Pack) para
 * poder entender después cómo organiza las rutas/paradas y categorizarlas.
 *
 * Objetivo de diseño: capturar de forma útil pero SIN acumular miles de logs
 * casi-idénticos cuando el repartidor tiene la app abierta durante horas. La
 * clave es **capturar sólo cuando la pantalla es realmente nueva**:
 *
 *  1. Recorre el árbol de nodos (con topes de profundidad/cantidad) y arma
 *     líneas planas `"[depth|Clase|viewId] texto ¦ cd:contentDesc"`. El
 *     `viewId` es el identificador que los devs de la app le pusieron a cada
 *     vista (ej. `rv_paradas`, `tv_direccion`) → es lo más estable para
 *     categorizar después.
 *  2. Calcula una **huella normalizada**: borra tokens volátiles (relojes,
 *     "hace N min", contadores, porcentajes, fechas) para que un cronómetro
 *     que tickea no cuente como pantalla nueva, pero un cambio real de
 *     contenido (otra dirección, otra parada) sí.
 *  3. Emite sólo si la huella no se vio recientemente (LRU), respetando un
 *     intervalo mínimo entre capturas y un tope diario.
 *
 * No hace I/O ni red: devuelve las líneas y el caller (el AccessibilityService)
 * las manda por [EventReporter] (que ya tiene cola offline). Todo el estado es
 * en memoria y por sesión del servicio.
 */
class ScreenStructureCapturer {

    data class Capture(val screenName: String, val lines: List<String>)

    companion object {
        private const val MAX_NODES = 220
        private const val MAX_DEPTH = 14
        private const val MAX_LINES = 70
        private const val MAX_TEXT_LEN = 100
        private const val LRU_SIZE = 128
        private const val MIN_EMIT_INTERVAL_MS = 3_000L
        private const val DAILY_CAP = 300

        // Prefijo del screenName para distinguir estas capturas de exploración
        // del resto de los eventos en el feed (se guardan bajo el tipo
        // global_app_opened, sin migración de enum). Filtrable con
        // screenName LIKE 'SNAP:%'.
        const val SNAP_PREFIX = "SNAP:"

        // Patrones volátiles que se normalizan ANTES de calcular la huella, para
        // que datos que cambian solos no disparen capturas nuevas.
        private val RE_CLOCK = Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""")
        private val RE_RELATIVE = Regex("""\bhace\s+\d+\s*\w*""", RegexOption.IGNORE_CASE)
        private val RE_DATE = Regex("""\b\d{1,2}/\d{1,2}(/\d{2,4})?\b""")
        private val RE_PERCENT = Regex("""\b\d+\s*%""")
        // Corridas largas de dígitos (ids, contadores, ETAs en segundos). Se
        // conservan los números cortos (1-3 dígitos) porque suelen ser alturas
        // de calle / números de parada, que SÍ queremos distinguir.
        private val RE_LONGNUM = Regex("""\d{4,}""")
    }

    // LRU de huellas ya emitidas (orden de acceso para evictar la más vieja).
    private val seen = object : LinkedHashMap<Int, Boolean>(LRU_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Boolean>?): Boolean =
            size > LRU_SIZE
    }

    private var lastEmitAt = 0L
    private var dayKey = 0L
    private var emittedToday = 0

    /**
     * Intenta capturar la pantalla actual. Devuelve la captura si es novedosa y
     * está dentro de los límites; null si se debe descartar (duplicada, muy
     * seguida, o superó el tope diario).
     */
    fun capture(root: AccessibilityNodeInfo?, activityShort: String?): Capture? {
        if (root == null) return null

        val now = System.currentTimeMillis()

        // Reset del contador diario al cambiar de día (buckets de 24h de reloj local).
        val today = now / 86_400_000L
        if (today != dayKey) {
            dayKey = today
            emittedToday = 0
        }
        if (emittedToday >= DAILY_CAP) return null
        if (now - lastEmitAt < MIN_EMIT_INTERVAL_MS) return null

        val lines = ArrayList<String>(MAX_LINES)
        val counter = intArrayOf(0)
        walk(root, 0, lines, counter)
        if (lines.isEmpty()) return null

        val fp = fingerprint(activityShort, lines)
        if (seen.containsKey(fp)) {
            // Refresca recencia (acceso) para que las pantallas frecuentes no se
            // evicten y vuelvan a capturarse.
            seen[fp] = true
            return null
        }
        seen[fp] = true
        lastEmitAt = now
        emittedToday++

        val screenName = (SNAP_PREFIX + (activityShort ?: "?")).take(120)
        return Capture(screenName, lines)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<String>,
        counter: IntArray,
    ) {
        if (counter[0] >= MAX_NODES || out.size >= MAX_LINES || depth > MAX_DEPTH) return
        counter[0]++

        val text = node.text?.toString()?.trim().orEmpty()
        val cd = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
        val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()

        // Sólo registramos nodos con señal: texto, descripción, o un viewId con
        // nombre (los contenedores con id como `rv_paradas` importan para la
        // estructura aunque no tengan texto).
        if (text.isNotBlank() || cd.isNotBlank() || viewId.isNotBlank()) {
            val sb = StringBuilder()
            sb.append('[').append(depth).append('|').append(cls).append('|').append(viewId).append(']')
            if (text.isNotBlank()) sb.append(' ').append(text.take(MAX_TEXT_LEN))
            if (cd.isNotBlank()) sb.append(" ¦cd:").append(cd.take(MAX_TEXT_LEN))
            if (node.isClickable) sb.append(" ¦clk")
            out.add(sb.toString())
        }

        for (i in 0 until node.childCount) {
            if (counter[0] >= MAX_NODES || out.size >= MAX_LINES) break
            node.getChild(i)?.let { child ->
                walk(child, depth + 1, out, counter)
                child.recycle()
            }
        }
    }

    /** Huella estable de la pantalla, con los tokens volátiles normalizados. */
    private fun fingerprint(activityShort: String?, lines: List<String>): Int {
        val sb = StringBuilder(activityShort ?: "?")
        for (line in lines) {
            sb.append('\n').append(normalizeVolatile(line))
        }
        return sb.toString().hashCode()
    }

    private fun normalizeVolatile(s: String): String {
        var t = s
        t = RE_CLOCK.replace(t, "TT")
        t = RE_RELATIVE.replace(t, "hace_N")
        t = RE_DATE.replace(t, "DD/MM")
        t = RE_PERCENT.replace(t, "N%")
        t = RE_LONGNUM.replace(t, "#")
        return t
    }
}
