package com.logistics.monitor.http

import android.content.Context
import com.logistics.monitor.auth.AuthInterceptor
import com.logistics.monitor.auth.AuthRepository
import com.logistics.monitor.auth.RefreshAuthenticator
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Cliente OkHttp único (singleton) que TODAS las clases del proyecto deberían
 * usar para hacer requests al back. Inyecta automáticamente el Bearer token y
 * maneja refresh transparente cuando recibe 401.
 *
 * El RealtimeStreamClient (SSE) usa su PROPIA instancia porque los streams
 * tienen requisitos de timeout distintos (readTimeout=0).
 */
object HttpClient {

    @Volatile private var instance: OkHttpClient? = null

    fun get(context: Context): OkHttpClient {
        return instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }
    }

    private fun build(context: Context): OkHttpClient {
        val auth = AuthRepository.get(context)
        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(auth.tokens))
            .authenticator(RefreshAuthenticator(auth))
            .build()
    }
}
