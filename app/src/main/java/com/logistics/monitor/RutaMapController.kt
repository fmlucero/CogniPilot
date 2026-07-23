package com.logistics.monitor

import android.content.Context
import androidx.core.content.ContextCompat
import com.logistics.monitor.data.entities.ParadaEntity
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * HU-57 — Encapsula el mapa OpenStreetMap (osmdroid) de la pantalla "Mi Ruta".
 *
 * Dibuja sobre el [MapView]:
 *  - un pin por parada (rojo, con info al tocar: orden + dirección + ventana),
 *  - una geocerca (círculo translúcido) por parada con el mismo radio que usa
 *    el [ParadaProximityWatcher] (50m) para que coincida con los nudges reales,
 *  - una polilínea que une las paradas en orden de recorrido,
 *  - un marcador de "mi posición" alimentado por el último fix de [LocationReporter].
 *
 * No tiene estado de negocio: la MainActivity le pasa las paradas cacheadas y la
 * posición, y este controlador sólo renderiza. Reusa el patrón "remote-first":
 * lo que llega de Room se pinta, sin lógica de fetch acá.
 */
class RutaMapController(
    private val map: MapView,
    private val context: Context,
) {
    companion object {
        // Mismo radio que ParadaProximityWatcher.DEFAULT_RADIUS_M para coherencia visual.
        private const val GEOFENCE_RADIUS_M = 50.0
        private const val DEFAULT_ZOOM = 15.0
    }

    private var meMarker: Marker? = null
    private var routePoints: List<GeoPoint> = emptyList()
    // Markers de parada por id, para poder centrar el mapa al tocar su card (interactividad HU-60).
    private val paradaMarkers = LinkedHashMap<String, Marker>()
    private var initialized = false
    // I-31: encuadre diferido hasta que el MapView tenga medidas reales.
    private var pendingBox: BoundingBox? = null
    private var firstLayoutHooked = false

    /** Configura el tile source y los gestos. Idempotente. */
    fun init() {
        if (initialized) return
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setUseDataConnection(true)
        map.controller.setZoom(DEFAULT_ZOOM)
        initialized = true
    }

    fun onResume() = map.onResume()
    fun onPause() = map.onPause()

    /**
     * Repinta todas las paradas + geocercas + ruta y, si hay, la posición actual.
     * [paradas] debe venir ordenada por `orden`. Las paradas sin coordenadas
     * (lat==0 && lng==0, todavía sin geocodificar) se descartan.
     */
    fun render(paradas: List<ParadaEntity>, mePos: Pair<Double, Double>?) {
        init()
        val accent = ContextCompat.getColor(context, R.color.cp_accent)
        // Mismo amarillo con ~20% de opacidad para el relleno de la geocerca.
        val accentFill = (accent and 0x00FFFFFF) or (0x33 shl 24)
        val validas = paradas.filter { it.lat != 0.0 || it.lng != 0.0 }

        map.overlays.clear()
        meMarker = null
        paradaMarkers.clear()

        val pts = validas.map { GeoPoint(it.lat, it.lng) }

        // Z-order de abajo hacia arriba: geocercas → ruta → pines → mi posición.
        // 1) Geocercas (fills translúcidos).
        for (gp in pts) {
            val circle = Polygon().apply {
                points = Polygon.pointsAsCircle(gp, GEOFENCE_RADIUS_M)
                fillPaint.color = accentFill
                outlinePaint.color = accent
                outlinePaint.strokeWidth = 2.5f
            }
            map.overlays.add(circle)
        }

        // 2) Polilínea de recorrido (orden de paradas).
        if (pts.size >= 2) {
            val line = Polyline().apply {
                setPoints(pts)
                outlinePaint.color = accent
                outlinePaint.strokeWidth = 5f
            }
            map.overlays.add(line)
        }

        // 3) Pines de parada (encima de la ruta para que el toque abra el info window).
        for (p in validas) {
            val marker = Marker(map).apply {
                position = GeoPoint(p.lat, p.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Parada ${p.orden}"
                snippet = buildString {
                    if (!p.direccion.isNullOrBlank()) appendLine(p.direccion)
                    val ventana = when {
                        p.ventanaDesde != null && p.ventanaHasta != null -> "🕒 ${p.ventanaDesde} – ${p.ventanaHasta}"
                        p.ventanaDesde != null -> "🕒 desde ${p.ventanaDesde}"
                        p.ventanaHasta != null -> "🕒 hasta ${p.ventanaHasta}"
                        else -> "Sin ventana horaria"
                    }
                    append(ventana)
                }.trim()
            }
            map.overlays.add(marker)
            paradaMarkers[p.id] = marker
        }

        routePoints = pts

        // Mi posición arriba de todo.
        mePos?.let { setMyPosition(it.first, it.second) }

        recenter()
        map.invalidate()
    }

    /**
     * Actualiza sólo el marcador de "mi posición" sin repintar la ruta.
     * Llamado desde el auto-refresh de 3s de la MainActivity con el último fix GPS.
     */
    fun updateMyPosition(lat: Double, lng: Double) {
        if (!initialized) return
        setMyPosition(lat, lng)
        map.invalidate()
    }

    private fun setMyPosition(lat: Double, lng: Double) {
        val gp = GeoPoint(lat, lng)
        val existing = meMarker
        if (existing != null) {
            existing.position = gp
            return
        }
        val marker = Marker(map).apply {
            position = gp
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = ContextCompat.getDrawable(context, R.drawable.ic_map_me)
            title = "Estás acá"
            setInfoWindow(null) // sin popup; es sólo el punto de posición
        }
        meMarker = marker
        map.overlays.add(marker)
    }

    /**
     * Centra y hace zoom sobre una parada concreta y abre su info window.
     * Llamado al tocar la card de la parada en la lista (interactividad HU-60).
     * No-op si la parada no tiene marker (sin coordenadas válidas).
     */
    fun focusOnParada(paradaId: String): Boolean {
        if (!initialized) return false
        val marker = paradaMarkers[paradaId] ?: return false
        // Cerramos cualquier info window abierto antes de abrir el de esta parada.
        for (m in paradaMarkers.values) m.closeInfoWindow()
        map.controller.setZoom(17.0)
        map.controller.animateTo(marker.position)
        marker.showInfoWindow()
        map.invalidate()
        return true
    }

    /** Encuadra el mapa sobre todas las paradas + mi posición. */
    fun recenter() {
        if (!initialized) return
        val all = ArrayList<GeoPoint>(routePoints)
        meMarker?.position?.let { all.add(it) }
        when {
            all.isEmpty() -> return
            all.size == 1 -> {
                map.controller.setZoom(16.0)
                map.controller.setCenter(all[0])
            }
            else -> {
                val box = BoundingBox.fromGeoPoints(all)
                // I-31: zoomToBoundingBox con el MapView sin medir (width/height 0
                // — al abrir la app con ruta, el tab "Mi Ruta" arranca GONE en el
                // ViewFlipper) hace que osmdroid derive un zoom degenerado y
                // Projection.getCloserPixel clave el main thread ~5,5s por cada
                // render. El map.post{} anterior esperaba al message queue, no al
                // layout. Con medidas válidas se encuadra ya; si no, se difiere al
                // primer layout real del MapView.
                if (box.latitudeSpan < 1e-6 && box.longitudeSpanWithDateLine < 1e-6) {
                    // Todas las paradas en el mismo punto: box de span 0 también
                    // degenera el zoom. Se trata como punto único.
                    map.controller.setZoom(16.0)
                    map.controller.setCenter(all[0])
                } else if (map.width > 0 && map.height > 0) {
                    applyBoundingBox(box)
                } else {
                    pendingBox = box
                    if (!firstLayoutHooked) {
                        firstLayoutHooked = true
                        map.addOnFirstLayoutListener { _, _, _, _, _ ->
                            pendingBox?.let { applyBoundingBox(it) }
                            pendingBox = null
                        }
                    }
                }
            }
        }
    }

    private fun applyBoundingBox(box: BoundingBox) {
        try {
            map.zoomToBoundingBox(box, false, 80)
        } catch (_: Exception) {
            map.controller.setCenter(box.centerWithDateLine)
        }
    }

    /** True si hay al menos una parada con coordenadas para mostrar. */
    fun hasContent(): Boolean = routePoints.isNotEmpty()

    /** True si el marcador de "mi posición" está colocado en el mapa. */
    fun hasMyPosition(): Boolean = meMarker != null
}
