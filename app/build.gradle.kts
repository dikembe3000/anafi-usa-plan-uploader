plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "edu.nick.anafiuploader"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.nick.anafiuploader"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.parrot.drone.groundsdk:groundsdk:8.4.2")
    runtimeOnly("com.parrot.drone.groundsdk:arsdkengine:8.4.2")
}
