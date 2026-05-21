plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.logistics.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cognipilot.cognipilot"
        minSdk = 23   // HU-03: EncryptedSharedPreferences requiere API 23+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // HU-18: sync propio sin Firebase
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // HU-18 fase 4: cliente SSE para realtime push
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // HU-03: storage cifrado de tokens, persistencia local de ruta/reglas, JSON
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    // Backport de java.time.* para minSdk 23
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // HU-41 — GPS reporting con FusedLocationProviderClient
    implementation(libs.play.services.location)
}
