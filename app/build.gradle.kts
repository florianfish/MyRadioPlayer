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
        // Version : variable VERSION_NAME (workflow de release) ou propriété -PappVersion (F-Droid), tag sans le « v ».
        // versionCode déduit du numéro (1.8.0 -> 10800) : identique quel que soit l'outil qui compile.
        val version = System.getenv("VERSION_NAME") ?: (project.findProperty("appVersion") as String?) ?: "1.0"
        versionName = version
        versionCode = version.split('.').map { it.toIntOrNull() ?: 0 }.let { (it + listOf(0, 0, 0)).take(3) }
            .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
            .coerceAtLeast(1)
    }
    // Deux variantes : « full » avec Google Cast (GitHub, Obtainium) et « fdroid » 100 % libre,
    // sans Cast ni services en ligne non libres (pochettes iTunes, icônes Google)
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "NON_FREE_NET", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "NON_FREE_NET", "false")
        }
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
    "fullImplementation"("androidx.media3:media3-cast:$media3") // Google Cast (non libre : pas dans la variante fdroid)
    implementation("androidx.appcompat:appcompat:1.7.1") // thème AppCompat (requis par les dialogues Cast)
}
