plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.filip.camfirestick"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.filip.camfirestick"
        minSdk = 25          // Fire OS 6+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Para instalar via sideload sem configurar keystore:
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")   // servidor web da configuração
    implementation("com.google.zxing:core:3.5.3")     // gera o QR code
}
