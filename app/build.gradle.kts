plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kydium.tecladoxp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kydium.tecladoxp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Llave fija: cada APK nuevo se instala encima del anterior sin desinstalar
    // (y sin perder tu nivel ni tu XP).
    signingConfigs {
        getByName("debug") {
            storeFile = file("llave-debug.p12")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    androidResources {
        noCompress += "txt"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
