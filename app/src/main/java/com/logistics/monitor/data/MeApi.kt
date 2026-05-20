package com.logistics.monitor.data

import android.content.Context
import com.logistics.monitor.R
import com.logistics.monitor.http.HttpClient
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Cliente HTTP para los endpoints /api/me/* del back.
 * Usa HttpClient (singleton) que ya tiene el AuthInterceptor.
 */
class MeApi(private val context: Context) {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val rutaAdapter: JsonAdapter<MiRutaDto> = moshi.adapter(MiRutaDto::class.java)
    private val reglasAdapter: JsonAdapter<MisReglasDto> = moshi.adapter(MisReglasDto::class.java)
    private val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    private fun baseUrl(): String =
        context.applicationContext.getString(R.string.backend_base_url).trimEnd('/')

    /** GET /api/me/ruta — devuelve null si HTTP 404 (sin asignación). */
    suspend fun getMiRuta(): MiRutaDto? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${baseUrl()}/api/me/ruta").get().build()
        HttpClient.get(context).newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> null
                !resp.isSuccessful -> throw RuntimeException("getMiRuta HTTP ${resp.code}")
                else -> rutaAdapter.fromJson(resp.body!!.string())
            }
        }
    }

    /** GET /api/me/reglas */
    suspend fun getMisReglas(): MisReglasDto = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${baseUrl()}/api/me/reglas").get().build()
        HttpClient.get(context).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("getMisReglas HTTP ${resp.code}")
            reglasAdapter.fromJson(resp.body!!.string())
                ?: throw RuntimeException("Respuesta vacía")
        }
    }

    /** Serializa el campo condicion (Map) a JSON para guardarlo en Room. */
    fun serializeCondicion(condicion: Map<String, Any?>?): String =
        if (condicion == null) "{}" else mapAdapter.toJson(condicion)
}

// ─── DTOs ───────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class MiRutaDto(
    val ruta: RutaDto,
    val paradas: List<ParadaDto>,
)

@JsonClass(generateAdapter = true)
data class RutaDto(
    val id: String,
    val nombre: String,
    val fecha: String,         // YYYY-MM-DD
    val empresaId: String,
)

@JsonClass(generateAdapter = true)
data class ParadaDto(
    val id: String,
    val orden: Int,
    val lat: Double,
    val lng: Double,
    val direccion: String? = null,
    val ventanaDesde: String? = null,
    val ventanaHasta: String? = null,
    val paquetes: List<PaqueteDto>,
)

@JsonClass(generateAdapter = true)
data class PaqueteDto(
    val id: String,
    val codigoMl: String,
    val descripcion: String? = null,
)

@JsonClass(generateAdapter = true)
data class MisReglasDto(val reglas: List<ReglaDto>)

@JsonClass(generateAdapter = true)
data class ReglaDto(
    val id: String,
    val nombre: String,
    val tipo: String,
    val accion: String,
    val condicion: Map<String, Any?>,
    val activa: Boolean,
    val rutaId: String? = null,
)
