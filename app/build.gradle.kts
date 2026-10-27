plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.k1epic.phonecast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.k1epic.phonecast"
        minSdk = 26
        // Android 12-friendly target for this first prototype.
        targetSdk = 32
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
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
