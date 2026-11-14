plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.florian.radio"
    compileSdk = 37
    defaultConfig {
        applicationId = "fr.florian.radio"
        minSdk = 26
        targetSdk = 34
        // Fournis par le workflow de release (numéro de run, tag sans le « v »)
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }
    // Clé fixe pour que chaque release s'installe par-dessus la précédente (sinon désinstallation = favoris perdus)
    signingConfigs {
        create("release") {
            System.getenv("KEYSTORE_FILE")?.let { storeFile = file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        // APK de test : suffixe pour ne pas le confondre avec une release, + n° du run CI quand il existe
        debug { versionNameSuffix = "-debug" + (System.getenv("GITHUB_RUN_NUMBER")?.let { ".$it" } ?: "") }
        release { signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME, affiché à côté du titre
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.caverock:androidsvg-aar:1.4") // logos SVG des stations

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-cast:$media3") // Google Cast
    implementation("androidx.appcompat:appcompat:1.7.1") // thème AppCompat requis par les dialogues Cast
}
