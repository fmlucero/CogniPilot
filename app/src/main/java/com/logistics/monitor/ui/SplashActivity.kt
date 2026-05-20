package com.logistics.monitor.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.logistics.monitor.MainActivity
import com.logistics.monitor.auth.AuthRepository

/**
 * Decide qué pantalla mostrar al arrancar la app:
 *   - Hay sesión → MainActivity
 *   - No hay sesión → LoginActivity
 *
 * Sin layout propio (transparente) — el flicker es imperceptible porque solo
 * decide y reenvía.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = if (AuthRepository.get(this).hasSession()) {
            MainActivity::class.java
        } else {
            LoginActivity::class.java
        }
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finish()
    }
}
