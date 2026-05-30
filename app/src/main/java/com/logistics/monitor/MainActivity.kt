package com.logistics.monitor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.logistics.monitor.auth.AuthRepository
import com.logistics.monitor.data.MeRepository
import com.logistics.monitor.ui.SplashActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pantalla principal del Monitor de Logística.
 *
 * Responsabilidades:
 *  - Verificar y solicitar permiso de overlay (SYSTEM_ALERT_WINDOW)
 *  - Redirigir al usuario a la configuración de Accesibilidad
 *  - Mostrar estado actual del servicio (activo/inactivo)
 *  - Iniciar/detener el ForegroundService de notificación
 *
 * HU-18: programa el ScheduleSyncWorker (WorkManager, cada 15 min) y corre un
 * polling de schedule cada 30s mientras la activity está en foreground.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY = 1001
        private const val FOREGROUND_POLL_INTERVAL_MS = 30_000L
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvSession: TextView
    private lateinit var btnLogout: Button
    private lateinit var btnToggleMonitor: Button
    private lateinit var btnConfigureOverlay: Button
    private lateinit var btnOpenAccessibility: Button
    private lateinit var btnToggleGlobal: Button
    private lateinit var globalModeRepository: GlobalModeRepository

    // HU-55 — navegación por pestañas. El ViewFlipper alterna las 4 secciones
    // (Inicio/Mi Ruta/Permisos/Perfil) sin fragments: la lógica sigue viviendo
    // en esta Activity, sólo cambia qué sección está visible.
    private lateinit var viewFlipper: ViewFlipper
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var tvSectionTitle: TextView

    private var foregroundPollJob: Job? = null
    private var statusRefreshJob: Job? = null
    private lateinit var realtimeClient: RealtimeStreamClient
    private lateinit var meRepository: MeRepository

    // HU-41 — launcher para pedir permiso de ubicación.
    // Si el user concede, arrancamos el LocationReporter; si no, sigue todo
    // funcionando pero sin reporte GPS al back.
    private var locationPermAskedThisSession = false
    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val anyGranted = grants.values.any { it }
        if (anyGranted) {
            Toast.makeText(this, "📍 Ubicación habilitada — la flota te ve en el mapa", Toast.LENGTH_SHORT).show()
            // Si el servicio ya está corriendo, arrancar el reporter ahora;
            // si no, va a arrancar cuando se active el monitor.
            if (LogisticsMonitoringService.isRunning) {
                LocationReporter.start(this)
            }
            // HU-43 — el flag location_perm cambió, reportar.
            CapabilitiesReporter.reportNow(this)
        } else {
            // Si rationale es false, el usuario marcó "no preguntes de nuevo".
            // Lo redirigimos a configuración para que pueda otorgarlo manualmente.
            val canShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            )
            if (canShowRationale) {
                Toast.makeText(this, "⚠️ Sin GPS el supervisor no te ve en el mapa", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "⚠️ Permiso denegado. Tap el botón \"Otorgar ubicación\" para ir a configuración.", Toast.LENGTH_LONG).show()
            }
        }
        updateStatusDisplay()
    }

    /** HU-41 — pide el permiso una vez por sesión si todavía no fue concedido. */
    private fun askLocationPermissionIfNeeded() {
        if (locationPermAskedThisSession) return
        if (LocationReporter.hasPermission(this)) return
        locationPermAskedThisSession = true
        locationPermLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }

    /** Abre la pantalla de configuración de la app — útil cuando el user marcó
     *  "no preguntes de nuevo" y necesita habilitar permisos manualmente. */
    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSession = findViewById(R.id.tvSession)
        btnLogout = findViewById(R.id.btnLogout)
        btnToggleMonitor = findViewById(R.id.btnToggleMonitor)
        btnConfigureOverlay = findViewById(R.id.btnConfigureOverlay)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnToggleGlobal = findViewById(R.id.btnToggleGlobal)
        viewFlipper = findViewById(R.id.viewFlipper)
        bottomNav = findViewById(R.id.bottomNav)
        tvSectionTitle = findViewById(R.id.tvSectionTitle)
        globalModeRepository = GlobalModeRepository(this)
        meRepository = MeRepository(this)

        setupTabs()
        setupButtons()
        updateGlobalButtonLabel()
        updateSessionDisplay()

        // HU-18: schedule del worker periódico (background sync cada 15 min)
        ScheduleSyncWorker.schedulePeriodic(this)
        // HU-10: drainer offline (15 min con constraint NetworkType.CONNECTED)
        OfflineDrainWorker.schedulePeriodic(this)
        // HU-18 fase 4: cliente SSE para realtime (latencia <100ms en foreground)
        realtimeClient = RealtimeStreamClient(this)

        // Cuando el servicio cambia su estado (onCreate/onDestroy), refrescamos
        // la UI sin esperar a onResume.
        LogisticsMonitoringService.onStateChange = {
            runOnUiThread { updateStatusDisplay() }
        }

        Log.i(TAG, "MainActivity creado")
    }

    override fun onResume() {
        super.onResume()
        updateStatusDisplay()
        updateSessionDisplay()
        startForegroundPolling()
        startStatusAutoRefresh()
        realtimeClient.connect()
        // HU-41 — pedir permiso GPS proactivamente al volver al foreground.
        // Una vez por sesión de la activity para no spamear al usuario.
        askLocationPermissionIfNeeded()
        // HU-41 — si ya hay permiso, arrancar el reporter aunque el monitor
        // foreground service NO esté activo. start() es idempotente. Mientras
        // la activity esté visible Android mantiene el proceso vivo y los
        // updates llegan. Para reporting en background sin activity, el user
        // tiene que activar el monitor (foregroundServiceType=location).
        if (LocationReporter.hasPermission(this)) {
            LocationReporter.start(this)
        }
        // HU-43 — reportar capabilities (overlay/acc/loc/notif/monitor). Es no-op
        // si nada cambió desde el último envío exitoso (>6h ago lo reenvía igual).
        CapabilitiesReporter.reportNow(this)
        // HU-10 — trigger oneshot del drainer al volver al foreground. Si hay
        // red, drena enseguida; si no hay, queda pendiente hasta que aparezca.
        OfflineDrainWorker.triggerOneShot(this)
        // Refresh de ruta/reglas en background — best effort, sin bloquear UI
        lifecycleScope.launch {
            val sync = meRepository.syncFromBackend()
            if (sync.rutaOk || sync.reglasOk) updateSessionDisplay()
        }
    }

    override fun onPause() {
        super.onPause()
        stopForegroundPolling()
        stopStatusAutoRefresh()
        realtimeClient.disconnect()
    }

    /** Refresca el TextView de status cada 3s mientras la activity está visible
     *  para que el contador "último fix hace Xs" se actualice en vivo. */
    private fun startStatusAutoRefresh() {
        statusRefreshJob?.cancel()
        statusRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(3_000L)
                updateStatusDisplay()
            }
        }
    }

    private fun stopStatusAutoRefresh() {
        statusRefreshJob?.cancel()
        statusRefreshJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        LogisticsMonitoringService.onStateChange = null
    }

    /**
     * HU-18: polling de schedule mientras la activity está visible.
     * Cuando detecta cambio respecto al snapshot local, dispara notif y
     * fuerza re-evaluación del AccessibilityService.
     */
    private fun startForegroundPolling() {
        foregroundPollJob?.cancel()
        foregroundPollJob = lifecycleScope.launch {
            // Primer poll inmediato al volver a foreground.
            tickPoll()
            while (isActive) {
                delay(FOREGROUND_POLL_INTERVAL_MS)
                tickPoll()
            }
        }
    }

    private fun stopForegroundPolling() {
        foregroundPollJob?.cancel()
        foregroundPollJob = null
    }

    private suspend fun tickPoll() {
        val changed = ScheduleSyncWorker.syncOnce(applicationContext)
        if (changed) {
            Log.i(TAG, "📡 Polling detectó cambio de schedule")
        }
        // HU-04 — bajar el TTL de sync de reglas (y ruta) a <=30s en foreground.
        // El criterio de la HU es "los dispositivos reciben la regla en la próxima
        // sincronización (máx 30 seg con conexión)" — esto lo cubre. En background
        // sigue corriendo el ScheduleSyncWorker cada 15 min.
        val syncResult = meRepository.syncFromBackend()
        if (syncResult.reglasOk || syncResult.rutaOk) {
            // Solo repintamos si UI ya esta visible.
            runOnUiThread { updateSessionDisplay() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * HU-55 — conecta la BottomNavigationView con el ViewFlipper.
     * Cada tab muestra la sección correspondiente (mismo índice) y actualiza
     * el subtítulo del header. No mueve la lógica: los controles de cada
     * sección siguen siendo los mismos views con sus listeners de setupButtons().
     */
    private fun setupTabs() {
        bottomNav.setOnItemSelectedListener { item ->
            val (idx, titleRes) = when (item.itemId) {
                R.id.tab_inicio -> 0 to R.string.tab_inicio
                R.id.tab_ruta -> 1 to R.string.tab_ruta
                R.id.tab_permisos -> 2 to R.string.tab_permisos
                R.id.tab_perfil -> 3 to R.string.tab_perfil
                else -> 0 to R.string.tab_inicio
            }
            viewFlipper.displayedChild = idx
            tvSectionTitle.setText(titleRes)
            true
        }
        // Arranca en Inicio.
        bottomNav.selectedItemId = R.id.tab_inicio
    }

    private fun setupButtons() {
        btnConfigureOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY)
            } else {
                Toast.makeText(this, "✅ Permiso overlay ya concedido", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Buscá \"Monitor Logística\" y actívalo",
                Toast.LENGTH_LONG
            ).show()
        }

        btnToggleMonitor.setOnClickListener {
            if (LogisticsMonitoringService.isRunning) {
                stopMonitoringService()
            } else {
                startMonitoringService()
            }
        }

        btnToggleGlobal.setOnClickListener {
            val newState = !globalModeRepository.isEnabled()
            globalModeRepository.setEnabled(newState)
            LogisticsAccessibilityService.applyGlobalMode(newState)
            updateGlobalButtonLabel()
            Toast.makeText(
                this,
                if (newState) "🌐 Modo global ACTIVADO — todas las apps reportan"
                else "🌐 Modo global desactivado — solo SC Pack",
                Toast.LENGTH_LONG
            ).show()
        }

        btnLogout.setOnClickListener { onLogoutPressed() }
    }

    /**
     * HU-03 — Logout: detiene servicios, borra tokens y DB, vuelve a SplashActivity.
     */
    private fun onLogoutPressed() {
        lifecycleScope.launch {
            if (LogisticsMonitoringService.isRunning) {
                stopMonitoringService()
            }
            realtimeClient.disconnect()
            stopForegroundPolling()
            ScheduleSyncWorker.cancel(applicationContext)
            OfflineDrainWorker.cancel(applicationContext)
            AuthRepository.get(this@MainActivity).logout()
            val intent = Intent(this@MainActivity, SplashActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }
    }

    /**
     * HU-03 — Pinta tarjeta de sesión: nombre + email del usuario + ruta cacheada.
     */
    private fun updateSessionDisplay() {
        val tokens = AuthRepository.get(this).tokens
        val nombre = tokens.getNombre() ?: "—"
        val email = tokens.getEmail() ?: ""
        val rol = tokens.getRol() ?: ""

        lifecycleScope.launch {
            val ruta = meRepository.getRutaCached()
            val reglas = meRepository.getReglasCachedActivas()
            val rutaTxt = if (ruta != null) {
                val paradas = meRepository.getParadasCached(ruta.id)
                "${ruta.nombre} (${ruta.fecha}) · ${paradas.size} paradas"
            } else {
                "Sin ruta asignada"
            }
            tvSession.text = buildString {
                append("👤 ").append(nombre)
                if (email.isNotEmpty()) append(" · ").append(email)
                if (rol.isNotEmpty()) append("\n🎫 Rol: ").append(rol)
                append("\n📍 ").append(rutaTxt)
                append("\n📋 ").append(reglas.size).append(" reglas activas")
            }
        }
    }

    private fun updateGlobalButtonLabel() {
        val enabled = globalModeRepository.isEnabled()
        btnToggleGlobal.text = if (enabled) "🌐 Modo global: ON" else "🌐 Modo global: OFF"
        btnToggleGlobal.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (enabled) R.color.cp_success else R.color.cp_bg_elev_2
        )
        btnToggleGlobal.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.cp_accent_text else R.color.cp_text,
            )
        )
    }

    private fun startMonitoringService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "⚠️ Primero otorgá el permiso de overlay", Toast.LENGTH_LONG).show()
            return
        }
        if (!LogisticsAccessibilityService.isServiceConnected) {
            Toast.makeText(this, "⚠️ Primero activá el servicio de accesibilidad", Toast.LENGTH_LONG).show()
            return
        }
        // HU-41 — si no hay permiso GPS, lo pedimos antes de arrancar.
        // El servicio se inicia igual (el monitor funciona sin GPS), pero
        // el reporter no postea hasta que haya permission.
        if (!LocationReporter.hasPermission(this)) {
            locationPermLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
        }
        val intent = Intent(this, LogisticsMonitoringService::class.java)
        startForegroundService(intent)
        updateStatusDisplay()
        Log.i(TAG, "Servicio foreground iniciado")
        // HU-43 — el flag monitor_running acaba de cambiar; reportar.
        CapabilitiesReporter.reportNow(this)
    }

    private fun stopMonitoringService() {
        stopService(Intent(this, LogisticsMonitoringService::class.java))
        updateStatusDisplay()
        Log.i(TAG, "Servicio foreground detenido")
        // HU-43 — el flag monitor_running acaba de cambiar; reportar.
        CapabilitiesReporter.reportNow(this)
    }

    private fun updateStatusDisplay() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = LogisticsAccessibilityService.isServiceConnected
        val serviceRunning = LogisticsMonitoringService.isRunning
        val locationOk = LocationReporter.hasPermission(this)
        val locationReporting = LocationReporter.isRunning()
        val lastFixAt = LocationReporter.lastFixAtMs()
        val lastPostCode = LocationReporter.lastPostStatusCode()

        val pendingOffline = EventReporter.pendingCount(this)
        val statusLines = buildString {
            appendLine(if (overlayOk) "✅ Permiso overlay: OK" else "❌ Permiso overlay: FALTA")
            appendLine(if (accessibilityOk) "✅ Accesibilidad: ACTIVA" else "❌ Accesibilidad: INACTIVA")
            appendLine(if (locationOk) "✅ Permiso ubicación: OK" else "⚠️ Permiso ubicación: NO concedido")
            // Línea de GPS — honesta: diferencia "esperando fix" vs "fix recibido"
            val gpsLine = when {
                !locationOk -> "📍 GPS: NO (falta permiso)"
                !locationReporting -> "📍 GPS: ⏸️ reporter detenido"
                lastFixAt == 0L -> "📍 GPS: ⏳ pidiendo updates — esperando primer fix (salí afuera si estás indoor)"
                else -> {
                    val sec = (System.currentTimeMillis() - lastFixAt) / 1000
                    val httpInfo = when {
                        lastPostCode == -1 -> ""
                        lastPostCode in 200..299 -> " (POST ✅)"
                        else -> " (⚠️ último POST HTTP $lastPostCode)"
                    }
                    "📍 GPS: ✅ último fix hace ${sec}s$httpInfo"
                }
            }
            appendLine(gpsLine)
            appendLine(if (serviceRunning) "✅ Servicio: CORRIENDO" else "⏸️ Servicio: DETENIDO")
            if (pendingOffline > 0) {
                appendLine("📥 Eventos offline pendientes: $pendingOffline (se envían al recuperar red)")
            }

            if (overlayOk && accessibilityOk) {
                appendLine("\n🟢 LISTO — Monitor funcionando")
                appendLine("Monitoreando: com.mercadoenvios.logistics")
            } else {
                appendLine("\n⚠️ Configuración incompleta")
                appendLine("Completá los pasos 1 y 2")
            }
        }

        tvStatus.text = statusLines
        btnToggleMonitor.text = if (serviceRunning) "⏹ Desactivar monitor" else "▶️ Activar monitor"
        btnToggleMonitor.isEnabled = overlayOk && accessibilityOk
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            val granted = Settings.canDrawOverlays(this)
            Toast.makeText(
                this,
                if (granted) "✅ Permiso overlay concedido" else "❌ Permiso overlay denegado",
                Toast.LENGTH_SHORT
            ).show()
            updateStatusDisplay()
            // HU-43 — overlay cambió, reportar inmediato.
            CapabilitiesReporter.reportNow(this)
        }
    }
}
