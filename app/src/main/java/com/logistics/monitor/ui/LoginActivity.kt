package com.logistics.monitor.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.logistics.monitor.DeviceIdProvider
import com.logistics.monitor.MainActivity
import com.logistics.monitor.R
import com.logistics.monitor.auth.AuthRepository
import com.logistics.monitor.data.MeRepository
import kotlinx.coroutines.launch

/**
 * HU-03 — Pantalla de login para repartidores.
 *
 * Flujo:
 *   1. Usuario ingresa email + password
 *   2. POST /api/auth/login — registra dispositivo automáticamente
 *   3. Si OK: descarga ruta + reglas → Room → MainActivity
 *   4. Si NO: error genérico (no revela si el email existe)
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvError = findViewById(R.id.tvError)
        progress = findViewById(R.id.progress)

        btnLogin.setOnClickListener { onLoginPressed() }
    }

    private fun onLoginPressed() {
        val email = etEmail.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()

        if (email.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.login_error_empty))
            return
        }

        setBusy(true)
        clearError()

        lifecycleScope.launch {
            try {
                val deviceUuid = DeviceIdProvider.get(this@LoginActivity)
                val auth = AuthRepository.get(this@LoginActivity)
                val ok = auth.login(email, password, deviceUuid)

                if (!ok) {
                    showError(getString(R.string.login_error_invalid))
                    setBusy(false)
                    return@launch
                }

                // Descargar ruta + reglas. Si falla, no bloqueamos el login —
                // queda con cache vacío y se reintenta desde MainActivity.
                val sync = MeRepository(this@LoginActivity).syncFromBackend()
                Log.i(TAG, "📥 Sync inicial: ruta=${sync.rutaOk}, reglas=${sync.reglasOk}")

                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "Login error", e)
                showError(getString(R.string.login_error_network))
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        btnLogin.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        etEmail.isEnabled = !busy
        etPassword.isEnabled = !busy
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = View.VISIBLE
    }

    private fun clearError() {
        tvError.visibility = View.GONE
        tvError.text = ""
    }

    companion object {
        private const val TAG = "LoginActivity"
    }
}
