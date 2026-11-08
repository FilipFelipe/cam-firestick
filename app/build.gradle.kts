import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versão: no GitHub Actions vem da tag (v1.2.3 -> 1.2.3 / 10203). Localmente usa o padrão abaixo.
val appVersionName = (findProperty("appVersionName") as String?) ?: "0.1.0"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 100

// Chave de release: keystore.properties na raiz (NÃO versionado). Sem ele, o release usa a chave de debug.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.filip.camfirestick"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.filipfelipe.centraldecameras"
        minSdk = 25          // Fire OS 6+
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        // Permite instalar um build de teste ao lado do app oficial: ./gradlew -PappIdSuffix=.test ...
        (project.findProperty("appIdSuffix") as String?)?.let { applicationIdSuffix = it }
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

base {
    archivesName.set("cam-firestick-v$appVersionName")
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")   // servidor web da configuração
    implementation("com.google.zxing:core:3.5.3")     // gera o QR code
}
