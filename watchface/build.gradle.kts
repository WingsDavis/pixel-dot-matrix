plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.watchface"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.aistudio.panicpomodoro.watchface"
        minSdk = 34  // WFF v2 requires API 34+
        targetSdk = 36
        versionCode = 6
        versionName = "1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug { }
    }
}

// WFF watch faces are pure XML — no dependencies needed
