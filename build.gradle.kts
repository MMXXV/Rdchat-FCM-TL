// Mantém o plugin Android gerado pelo Android Studio e adiciona somente Firebase Google Services.
plugins {
    alias(libs.plugins.android.application) apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
}
