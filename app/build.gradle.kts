import java.util.Properties

// Migração temporária: materializar o HTML resultante antes de retirar os patches do Gradle.

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
        buildConfigField("String", "TELEGRAM_APPLICATION_VERSION", "\"1.0.7\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Mantém o APK universal menor ao comprimir as bibliotecas .so.
    // O Android as extrai na instalação, preservando o carregamento nativo.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // AGP 9.x compila Kotlin com o suporte Kotlin integrado; não aplicar kotlin-android.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Gera um único APK com todas as bibliotecas nativas entregues pelas dependências.
    // A verificação no CI exige as quatro ABIs Android: ARM 32/64 e x86 32/64.

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    testOptions { animationsDisabled = true }
}

val verifyTelegramApiCredentials = tasks.register("verifyTelegramApiCredentials") {
    doLast {
        require(telegramApiId > 0) { "TELEGRAM_API_ID ausente ou inválido." }
        require(Regex("^[0-9a-fA-F]{32}$").matches(telegramApiHash.trim())) {
            "TELEGRAM_API_HASH ausente ou inválido. Configure TELEGRAM_API_HASH no ambiente do Gradle."
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyTelegramApiCredentials)
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

    // Native WebRTC media engine used together with TDLib call signaling.
    implementation("io.github.pytgcalls:ntgcalls:3.0.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
