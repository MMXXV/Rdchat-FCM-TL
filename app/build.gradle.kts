import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

val telegramProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

val telegramApiIdText =
    providers.environmentVariable("TELEGRAM_API_ID").orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("telegramApiId").orNull?.takeIf { it.isNotBlank() }
        ?: telegramProperties.getProperty("telegramApiId")?.takeIf { it.isNotBlank() }
        ?: "34968764" // api_id já usado pelo projeto; não é segredo.

val telegramApiHash =
    providers.environmentVariable("TELEGRAM_API_HASH").orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty("telegramApiHash").orNull?.takeIf { it.isNotBlank() }
        ?: telegramProperties.getProperty("telegramApiHash")?.takeIf { it.isNotBlank() }
        ?: ""
val telegramApiId = telegramApiIdText.toIntOrNull() ?: 0
val escapedTelegramApiHash = telegramApiHash.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.example.rdchat"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.example.rdchat"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$escapedTelegramApiHash\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // AGP 9.x compila Kotlin com o suporte Kotlin integrado; não aplicar kotlin-android.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Gera APKs separados por arquitetura para não embutir todas as bibliotecas TDLib
    // em um único arquivo. Mantém suporte a ARM e x86 em artefatos independentes.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    testOptions { animationsDisabled = true }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // TDLib JNI + Java API, pinned to an immutable commit; includes arm64-v8a and x86_64.
    implementation("com.github.capullo-tech:lib-tdlib-android:7dede3f1a716b7398caf79acd413ad9d5331f079")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
