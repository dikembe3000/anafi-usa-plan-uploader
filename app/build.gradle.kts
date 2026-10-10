plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "edu.nick.anafiuploader"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.nick.anafiuploader"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.parrot.drone.groundsdk:groundsdk:8.4.2")
    runtimeOnly("com.parrot.drone.groundsdk:arsdkengine:8.4.2")
}
