package com.logistics.monitor.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Cuando el back responde 401, intenta hacer refresh y reintentar con el access
 * token nuevo. Si el refresh también falla, devuelve null → OkHttp deja pasar el
 * 401 al caller y la app debe redirigir a LoginActivity.
 *
 * OkHttp llama al authenticator de a una request por vez (sync), por eso
 * usamos runBlocking — no es bonito pero es el patrón estándar.
 */
class RefreshAuthenticator(private val auth: AuthRepository) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Evitar loops: si la request original ya tenía Authorization y aún así dio 401,
        // intentamos refresh UNA vez (responseCount > 2 → ya reintentamos, abandonar).
        if (responseCount(response) >= 2) return null

        val refreshed = runBlocking { auth.tryRefresh() }
        if (!refreshed) return null

        val newToken = auth.tokens.getAccessToken() ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun responseCount(resp: Response): Int {
        var count = 1
        var current: Response? = resp.priorResponse
        while (current != null) {
            count++
            current = current.priorResponse
        }
        return count
    }
}
