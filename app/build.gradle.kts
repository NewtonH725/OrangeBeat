plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.crossreel"
    compileSdk = 37

    // Protocollo di versionamento ufficiale Orange-Beat (vX.Y.Z):
    // - X (versionMajor): Incrementa (+1, azzerando Y e Z) SOLO per introduzione di nuove funzionalità.
    // - Y (versionMinor): Incrementa (+1, azzerando Z) per upgrade sostanziosi e miglioramenti importanti.
    // - Z (versionPatch): Incrementa (+1) solo per bug fix, correzioni e upgrade minori.
    val versionMajor = 3 // Incrementa SOLO per introduzione di nuove funzionalità (v3.0.0 Desktop Ubuntu & Windows + Multi-Platform OTA)
    val versionMinor = 0 // Incrementa per upgrade sostanziosi e più importanti
    val versionPatch = 0 // Incrementa per bug fix e upgrade minori

    defaultConfig {
        applicationId = "com.example.crossreel"
        minSdk = 35
        targetSdk = 37
        versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch // 30000 per v3.0.0
        versionName = "v$versionMajor.$versionMinor.$versionPatch" // "v3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    base {
        archivesName.set("OrangeBeat-v$versionMajor.$versionMinor.$versionPatch-android")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    
    // Media3 Transformer per il montaggio video
    val media3_version = "1.4.1"
    implementation("androidx.media3:media3-transformer:$media3_version")
    implementation("androidx.media3:media3-effect:$media3_version")
    implementation("androidx.media3:media3-common:$media3_version")
    
    // ML Kit Pose Detection (modello rapido single image)
    implementation("com.google.mlkit:pose-detection:17.0.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}