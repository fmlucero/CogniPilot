package com.logistics.monitor.auth

import android.content.Context
import com.logistics.monitor.R
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP minimalista para los endpoints /api/auth/{login,refresh,logout} del back.
 *
 * Usa su propio OkHttpClient (sin AuthInterceptor) porque estos endpoints son
 * los que justamente emiten el token — meter el interceptor llevaría a loop.
 */
class AuthApi(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val loginReqAdapter: JsonAdapter<LoginRequest> = moshi.adapter(LoginRequest::class.java)
    private val loginResAdapter: JsonAdapter<LoginResponse> = moshi.adapter(LoginResponse::class.java)
    private val refreshReqAdapter: JsonAdapter<RefreshRequest> = moshi.adapter(RefreshRequest::class.java)
    private val refreshResAdapter: JsonAdapter<RefreshResponse> = moshi.adapter(RefreshResponse::class.java)

    private fun baseUrl(): String =
        context.applicationContext.getString(R.string.backend_base_url).trimEnd('/')

    /**
     * POST /api/auth/login — devuelve null si las credenciales son inválidas
     * (HTTP 401). Cualquier otro error de red lanza excepción.
     */
    suspend fun login(req: LoginRequest): LoginResponse? = withContext(Dispatchers.IO) {
        val body = loginReqAdapter.toJson(req).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("${baseUrl()}/api/auth/login")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            when {
                resp.code == 401 -> null
                !resp.isSuccessful -> throw RuntimeException("Login HTTP ${resp.code}")
                else -> {
                    val text = resp.body?.string() ?: throw RuntimeException("Empty body")
                    loginResAdapter.fromJson(text)
                }
            }
        }
    }

    /**
     * POST /api/auth/refresh — devuelve null si el refresh token está vencido/inválido (401).
     * Usado por RefreshAuthenticator cuando una request normal recibe 401.
     */
    suspend fun refresh(refreshToken: String): RefreshResponse? = withContext(Dispatchers.IO) {
        val body = refreshReqAdapter.toJson(RefreshRequest(refreshToken)).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("${baseUrl()}/api/auth/refresh")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            when {
                resp.code == 401 -> null
                !resp.isSuccessful -> throw RuntimeException("Refresh HTTP ${resp.code}")
                else -> {
                    val text = resp.body?.string() ?: throw RuntimeException("Empty body")
                    refreshResAdapter.fromJson(text)
                }
            }
        }
    }

    /** POST /api/auth/logout — idempotente, no requiere auth. Best-effort. */
    suspend fun logout(): Unit = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}/api/auth/logout")
            .post(byteArrayOf().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(request).execute().close()
        } catch (_: Exception) {
            // best-effort
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

// ─── DTOs ───────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val email: String,
    val password: String,
    val deviceUuid: String? = null,
    val fcmToken: String? = null,
    val modelo: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val user: UserDto,
    val dispositivoId: String? = null,
    val accessToken: String,
    val refreshToken: String,
)

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: String,
    val email: String,
    val nombre: String,
    val rol: String,
    val empresaId: String? = null,
)

@JsonClass(generateAdapter = true)
data class RefreshRequest(val refreshToken: String)

@JsonClass(generateAdapter = true)
data class RefreshResponse(val accessToken: String, val refreshToken: String)
