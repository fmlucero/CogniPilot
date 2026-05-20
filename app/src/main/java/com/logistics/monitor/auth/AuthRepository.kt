package com.logistics.monitor.auth

import android.content.Context
import android.os.Build
import com.logistics.monitor.data.AppDatabase

/**
 * HU-03 — Orquesta login/logout y mantiene el estado de sesión.
 *
 * Es singleton para que el AuthInterceptor (en cualquier OkHttpClient) y
 * MainActivity vean siempre la misma TokenStorage.
 */
class AuthRepository private constructor(context: Context) {

    private val appCtx = context.applicationContext
    val tokens = TokenStorage(appCtx)
    private val api = AuthApi(appCtx)

    /**
     * Hace login + auto-registra el dispositivo (deviceUuid + modelo + versión).
     * Retorna true si fue exitoso, false si las credenciales son inválidas.
     * Lanza excepción ante error de red u otro.
     */
    suspend fun login(email: String, password: String, deviceUuid: String): Boolean {
        val req = LoginRequest(
            email = email.trim(),
            password = password,
            deviceUuid = deviceUuid,
            modelo = "${Build.MANUFACTURER} ${Build.MODEL}",
            osVersion = "Android ${Build.VERSION.RELEASE}",
            appVersion = APP_VERSION,
        )
        val resp = api.login(req) ?: return false
        tokens.saveTokens(resp.accessToken, resp.refreshToken)
        tokens.saveUser(
            userId = resp.user.id,
            email = resp.user.email,
            nombre = resp.user.nombre,
            rol = resp.user.rol,
            empresaId = resp.user.empresaId,
        )
        return true
    }

    /**
     * Logout: notifica al back (best-effort), borra tokens + datos locales.
     * NO borra el device_id UUID (vive en otras prefs).
     */
    suspend fun logout() {
        try {
            api.logout()
        } catch (_: Exception) {
            // best-effort
        }
        tokens.clear()
        AppDatabase.get(appCtx).clearAllTables()
    }

    /** True si hay tokens guardados (no valida expiración aquí; el back lo hace). */
    fun hasSession(): Boolean = tokens.hasSession()

    /** Llamado por RefreshAuthenticator cuando una request da 401. */
    suspend fun tryRefresh(): Boolean {
        val rt = tokens.getRefreshToken() ?: return false
        val resp = try {
            api.refresh(rt)
        } catch (_: Exception) {
            null
        } ?: return false
        tokens.saveTokens(resp.accessToken, resp.refreshToken)
        return true
    }

    companion object {
        private const val APP_VERSION = "0.3.0-hu03"

        @Volatile private var INSTANCE: AuthRepository? = null

        fun get(context: Context): AuthRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthRepository(context).also { INSTANCE = it }
            }
    }
}
