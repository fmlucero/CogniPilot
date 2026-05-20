package com.logistics.monitor.auth

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Inyecta `Authorization: Bearer <access_token>` en cada request, si hay sesión.
 *
 * No bloquea requests si no hay token — sigue su curso y el back devolverá 401,
 * que el RefreshAuthenticator o el caller manejan.
 */
class AuthInterceptor(private val tokens: TokenStorage) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // No interceptamos auth/* (login, refresh, logout) — son los que crean el token.
        val pathSegments = request.url.encodedPathSegments
        val isAuthEndpoint = pathSegments.size >= 2 && pathSegments[0] == "api" && pathSegments[1] == "auth"
        if (isAuthEndpoint) return chain.proceed(request)

        val token = tokens.getAccessToken()
        val withAuth = if (!token.isNullOrBlank()) {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            request
        }
        return chain.proceed(withAuth)
    }
}
