plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.raceengineer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.raceengineer.app"
        minSdk = 33
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
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
    // On-device text reading (the language model is bundled, no internet needed)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
}
